package io.muserver;

import io.muserver.rest.MuRuntimeDelegate;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.ServiceUnavailableException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * A request and response exchange between a client and the server
 */
class HttpExchange implements ResponseInfo, Exchange {

    private static final Map<String, String> exceptionMessageMap = new HashMap<>();

    static {
        MuRuntimeDelegate.ensureSet();
        exceptionMessageMap.put(new NotFoundException().getMessage(), "This page is not available. Sorry about that.");
    }

    private static final Logger log = LoggerFactory.getLogger(HttpExchange.class);
    final NettyRequestAdapter request;
    final NettyResponseAdaptor response;
    private final HttpConnection connection;
    private final long startTime = System.currentTimeMillis();
    private volatile long endTime;
    private volatile HttpExchangeState state = HttpExchangeState.IN_PROGRESS;
    private final List<HttpExchangeStateChangeListener> listeners = new CopyOnWriteArrayList<>();

    HttpExchange(HttpConnection connection, NettyRequestAdapter request, NettyResponseAdaptor response) {
        this.connection = connection;
        this.request = request;
        this.response = response;
        request.addChangeListener((exchange, newState) -> onReqOrRespStateChange(newState, null));
        response.addChangeListener((exchange, newState) -> onReqOrRespStateChange(null, newState));
    }

    void addChangeListener(HttpExchangeStateChangeListener listener) {
        this.listeners.add(listener);
    }

    private void onReqOrRespStateChange(RequestState requestChanged, ResponseState responseChanged) {
        log.info("HE state change. request=" + requestChanged + " ; resp=" + responseChanged);
        RequestState reqState = request.requestState();
        ResponseState respState = response.responseState();
        if (reqState == RequestState.ERROR || (respState.endState() && !respState.completedSuccessfully())) {
            onEnded(HttpExchangeState.ERRORED);
        } else if (reqState.endState() && respState == ResponseState.UPGRADED) {
            onEnded(HttpExchangeState.UPGRADED);
        } else if (reqState.endState() && respState.endState()) {
            onEnded(HttpExchangeState.COMPLETE);
        } else if (responseChanged != null && responseChanged.endState()) {
            request.discardInputStreamIfNotConsumed();
        }
    }

    private void onEnded(HttpExchangeState endState) {
        if (this.state.endState()) {
            throw new IllegalStateException("Cannot end an exchange that was already ended. Previous state=" + this.state + "; new state=" + endState);
        }
        this.state = endState;
        this.endTime = System.currentTimeMillis();
        for (HttpExchangeStateChangeListener listener : listeners) {
            listener.onStateChange(this, endState);
        }
    }

    public Future<Void> complete(boolean forceDisconnect) {
        if (response.outputState().endState()) {
            if (log.isDebugEnabled()) {
                log.debug("AsyncContext.complete called twice for " + request + " where state was " + response.outputState(), new MuException(""));
            }
            return null;
        } else {
            return response.complete(forceDisconnect);
        }
    }

    void onCancelled(ResponseState reason) {
        if (!response.outputState().endState()) {
            response.onCancelled(reason);
            request.onCancelled(reason, new MuException("Cancell: " + reason.name()));
        } else {
            log.warn("Cancelled called after end state was " + response.outputState());
        }
    }

    @Override
    public long duration() {
        long end = endTime;
        if (end == 0) end = System.currentTimeMillis();
        return end - request.startTime();
    }

    @Override
    public boolean completedSuccessfully() {
        return response.outputState().completedSuccessfully();
    }

    @Override
    public MuRequest request() {
        return request;
    }

    @Override
    public MuResponse response() {
        return response;
    }

    @Override
    public String toString() {
        return "ResponseInfo{" +
            "request=" + request +
            ", response=" + response +
            '}';
    }

    @Override
    public void onMessage(ChannelHandlerContext ctx, Object msg) throws UnexpectedMessageException {
        if (!(msg instanceof HttpContent)) {
            throw new UnexpectedMessageException(this, msg);
        }
        HttpContent content = (HttpContent) msg;
        ByteBuf byteBuf = content.content().retain();
        boolean last = msg instanceof LastHttpContent;
        System.out.println("last = " + last);
        if (last) {
            System.out.println();
        }
        DoneCallback doneCallback = error -> {
            if (error == null) {
                if (last) {
                    request.setState(RequestState.COMPLETE);
                } else {
                    ctx.channel().read();
                }
            } else {
                request.setState(RequestState.ERROR);
                onException(ctx, error);
            }
            byteBuf.release();
        };
        try {
            request.onRequestBodyRead(byteBuf, last, doneCallback);
        } catch (Exception e) {
            try {
                doneCallback.onComplete(e);
            } catch (Exception exception) {
                log.error("Unhandled callback error", exception);
            }
        }
    }

    @Override
    public void onIdleTimeout(ChannelHandlerContext ctx, IdleStateEvent ise) {
        if (ise.state() == IdleState.WRITER_IDLE) {
            onCancelled(ResponseState.TIMED_OUT);
            log.info("Closed " + request + " (from " + request.remoteAddress() + ") because the idle timeout specified in MuServerBuilder#withIdleTimeout is exceeded.");
        } else if (ise.state() == IdleState.READER_IDLE && !request.requestState().endState()) {
            request.onReadTimeout();
        }
    }


    @Override
    public HttpConnection connection() {
        return connection;
    }

    public HttpExchangeState state() {
        return state;
    }

    static HttpExchange create(MuServerImpl server, String proto, ChannelHandlerContext ctx, Http1Connection connection,
                               HttpRequest nettyRequest, NettyHandlerAdapter nettyHandlerAdapter, MuStatsImpl connectionStats,
                               HttpExchangeStateChangeListener stateChangeListener) throws InvalidHttpRequestException {
        ServerSettings settings = server.settings();
        throwIfInvalid(settings, ctx, nettyRequest);

        Method method = getMethod(nettyRequest.method());
        Http1Headers headers = new Http1Headers(nettyRequest.headers());

        String relativeUri = getRelativeUrl(nettyRequest.uri());

        NettyRequestAdapter muRequest = new NettyRequestAdapter(ctx, nettyRequest, headers, method,
            proto, relativeUri, HttpUtil.isKeepAlive(nettyRequest), headers.get(HeaderNames.HOST));

        MuStatsImpl serverStats = server.stats;
        Http1Response muResponse = new Http1Response(ctx, muRequest, new Http1Headers());

        HttpExchange httpExchange = new HttpExchange(connection, muRequest, muResponse);
        muRequest.setExchange(httpExchange);
        muResponse.setExchange(httpExchange);
        httpExchange.addChangeListener(stateChangeListener);

        if (settings.block(muRequest)) {
            throw new InvalidHttpRequestException(429, "429 Too Many Requests");
        }

        DoneCallback addedToExecutorCallback = error -> {
            if (error == null) {
                serverStats.onRequestStarted(httpExchange.request);
                connectionStats.onRequestStarted(httpExchange.request);
            } else {
                serverStats.onRejectedDueToOverload();
                connectionStats.onRejectedDueToOverload();
                try {
                    dealWithUnhandledException(muRequest, muResponse, new ServiceUnavailableException());
                } catch (Exception e) {
                    ctx.close();
                }
            }
        };
        nettyHandlerAdapter.onHeaders(addedToExecutorCallback, httpExchange);
        return httpExchange;
    }

    static String getRelativeUrl(String nettyUri) throws InvalidHttpRequestException {
        try {
            URI requestUri = new URI(nettyUri).normalize();
            String s = requestUri.getRawPath();
            if (Mutils.nullOrEmpty(s)) {
                s = "/";
            }
            String q = requestUri.getRawQuery();
            if (q != null) {
                s += "?" + q;
            }
            return s;
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("Invalid request URL " + nettyUri);
            throw new InvalidHttpRequestException(400, "400 Bad Request");
        }
    }

    static Method getMethod(HttpMethod nettyMethod) throws InvalidHttpRequestException {
        Method method;
        try {
            method = Method.fromNetty(nettyMethod);
        } catch (IllegalArgumentException e) {
            throw new InvalidHttpRequestException(405, "405 Method Not Allowed");
        }
        return method;
    }

    private static void throwIfInvalid(ServerSettings settings, ChannelHandlerContext ctx, HttpRequest nettyRequest) throws InvalidHttpRequestException {
        if (nettyRequest.decoderResult().isFailure()) {
            Throwable cause = nettyRequest.decoderResult().cause();
            if (cause instanceof TooLongFrameException) {
                if (cause.getMessage().contains("header is larger")) {
                    throw new InvalidHttpRequestException(431, "431 Request Header Fields Too Large");
                } else if (cause.getMessage().contains("line is larger")) {
                    throw new InvalidHttpRequestException(414, "414 Request-URI Too Long");
                }
            }
            if (log.isDebugEnabled()) log.debug("Invalid http request received", cause);
            throw new InvalidHttpRequestException(500, "Invalid HTTP request received");
        }

        String contentLenDecl = nettyRequest.headers().get("Content-Length");
        if (HttpUtil.is100ContinueExpected(nettyRequest)) {
            long requestBodyLen = contentLenDecl == null ? -1L : Long.parseLong(contentLenDecl, 10);
            if (requestBodyLen <= settings.maxRequestSize) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.CONTINUE));
            } else {
                throw new InvalidHttpRequestException(417, "417 Expectation Failed - request too large");
            }
        }

        if (!nettyRequest.headers().contains(HttpHeaderNames.HOST)) {
            throw new InvalidHttpRequestException(400, "400 Bad Request - no Host header");
        }
        if (contentLenDecl != null) {
            long cld = Long.parseLong(contentLenDecl, 10);
            if (cld > settings.maxRequestSize) {
                throw new InvalidHttpRequestException(413, "413 Payload Too Large");
            }
        }
    }

    @Override
    public void onException(ChannelHandlerContext ctx, Throwable cause) {
        log.info("onException " + cause.getMessage());
        dealWithUnhandledException(request, response, cause);
    }

    @Override
    public void onConnectionEnded(ChannelHandlerContext ctx) {
        log.info("onConnectionEnded");
        if (!response.outputState().endState()) {
            onCancelled(ResponseState.CLIENT_DISCONNECTED);
        }
        if (!request.requestState().endState()) {
            request.onCancelled(ResponseState.CLIENT_DISCONNECTED, new ClientDisconnectedException());
        }
    }


    static boolean dealWithUnhandledException(NettyRequestAdapter request, NettyResponseAdaptor response, Throwable ex) {

        boolean forceDisconnect = response instanceof Http1Response;

        if (response.hasStartedSendingData()) {
            if (!response.responseState().endState()) {
                response.onCancelled(ResponseState.ERRORED);
            }
            if (!request.requestState().endState()) {
                request.onCancelled(ResponseState.ERRORED, ex);
            }
        } else {
            WebApplicationException wae;
            if (ex instanceof WebApplicationException) {
                forceDisconnect = false;
                wae = (WebApplicationException) ex;
            } else {
                String errorID = "ERR-" + UUID.randomUUID().toString();
                log.info("Sending a 500 to the client with ErrorID=" + errorID + " for " + request, ex);
                wae = new InternalServerErrorException("Oops! An unexpected error occurred. The ErrorID=" + errorID);
            }
            Response exResp = wae.getResponse();
            if (exResp == null) {
                exResp = Response.serverError().build();
            }

            response.status(exResp.getStatus());

            MuRuntimeDelegate.writeResponseHeaders(request.uri(), exResp, response);

            if (forceDisconnect) {
                response.headers().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
            }
            response.contentType(ContentTypes.TEXT_HTML_UTF8);
            String message = wae.getMessage();
            message = exceptionMessageMap.getOrDefault(message, message);
            response.write("<h1>" + exResp.getStatus() + " " + exResp.getStatusInfo().getReasonPhrase() + "</h1><p>" +
                Mutils.htmlEncode(message) + "</p>");
        }
        return forceDisconnect;
    }

    public long startTime() {
        return startTime;
    }
}

enum HttpExchangeState {
    IN_PROGRESS(false), COMPLETE(true), ERRORED(true), UPGRADED(true);
    private final boolean endState;

    HttpExchangeState(boolean endState) {
        this.endState = endState;
    }

    public boolean endState() {
        return endState;
    }
}

interface HttpExchangeStateChangeListener {
    void onStateChange(HttpExchange exchange, HttpExchangeState newState);
}