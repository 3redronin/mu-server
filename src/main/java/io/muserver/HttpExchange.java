package io.muserver;

import io.muserver.rest.MuRuntimeDelegate;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;

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
    private final NettyHandlerAdapter nettyHandlerAdapter;
    final NettyRequestAdapter request;
    final NettyResponseAdaptor response;
    GrowableByteBufferInputStream requestBody;
    private final HttpConnection connection;

    HttpExchange(HttpConnection connection, NettyHandlerAdapter nettyHandlerAdapter, NettyRequestAdapter request, NettyResponseAdaptor response) {
        this.connection = connection;
        this.nettyHandlerAdapter = nettyHandlerAdapter;
        this.request = request;
        this.response = response;
    }

    public Future<Void> complete(boolean forceDisconnect) {
        if (response.outputState().endState()) {
            log.warn("AsyncContext.complete called twice for " + request + " where state was " + response.outputState(), new MuException("WHat"));
            return null;
        } else {
            return response.complete(forceDisconnect);
        }
    }

    void onCancelled(ResponseState reason) {
        if (!response.outputState().endState()) {
            response.onCancelled(reason);
            request.onCancelled(reason);
        } else {
            log.warn("Cancelled called after end state was " + response.outputState());
        }
    }

    @Override
    public long duration() {
        long end = response.endTime;
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
        ByteBuf byteBuf = content.content();

        NettyHandlerAdapter.passDataToHandler(byteBuf, this, error -> {
            if (error == null) {
                ctx.channel().read();
            } else {
                onException(ctx, error);
            }
        });
        if (msg instanceof LastHttpContent) {
            nettyHandlerAdapter.onRequestComplete(this);
        }
    }

    @Override
    public void onIdleTimeout(ChannelHandlerContext ctx, IdleStateEvent ise) {
        onCancelled(ResponseState.TIMED_OUT);
        log.info("Closed " + request + " (from " + request.remoteAddress() + ") because the idle timeout specified in MuServerBuilder#withIdleTimeout is exceeded.");
    }


    @Override
    public HttpConnection connection() {
        return connection;
    }

    static HttpExchange create(MuServerImpl server, String proto, ChannelHandlerContext ctx, Http1Connection connection,
                               HttpRequest nettyRequest, NettyHandlerAdapter nettyHandlerAdapter, MuStatsImpl connectionStats,
                               NettyResponseAdaptor.StateChangeListener stateChangeListener) throws InvalidHttpRequestException {
        ServerSettings settings = server.settings();
        throwIfInvalid(settings, ctx, nettyRequest);

        Method method = getMethod(nettyRequest);
        Http1Headers headers = new Http1Headers(nettyRequest.headers());

        String relativeUri = getRelativeUrl(nettyRequest);

        NettyRequestAdapter muRequest = new NettyRequestAdapter(ctx, ctx.channel(), nettyRequest, headers, server, method,
            proto, relativeUri, HttpUtil.isKeepAlive(nettyRequest), headers.get(HeaderNames.HOST), nettyRequest.protocolVersion().text());


        MuStatsImpl serverStats = server.stats;
        Http1Response muResponse = new Http1Response(ctx, muRequest, new Http1Headers());

        HttpExchange httpExchange = new HttpExchange(connection, nettyHandlerAdapter, muRequest, muResponse);
        muRequest.setExchange(httpExchange);
        muResponse.setExchange(httpExchange);

        if (settings.block(muRequest)) {
            throw new InvalidHttpRequestException(429, "429 Too Many Requests");
        }

        muResponse.addChangeListener(stateChangeListener);
        DoneCallback addedToExecutorCallback = error -> {
            if (!WebSocketHandler.isWebSocketUpgrade(muRequest)) {
                ctx.channel().read();
            }
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
        nettyHandlerAdapter.onHeaders(addedToExecutorCallback, httpExchange, httpExchange.request.headers());
        return httpExchange;
    }

    private static String getRelativeUrl(HttpRequest nettyRequest) throws InvalidHttpRequestException {
        try {
            URI requestUri = new URI(nettyRequest.uri()).normalize();
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
            if (log.isDebugEnabled()) log.debug("Invalid request URL " + nettyRequest.uri());
            throw new InvalidHttpRequestException(400, "400 Bad Request");
        }
    }

    private static Method getMethod(HttpRequest nettyRequest) throws InvalidHttpRequestException {
        Method method;
        try {
            method = Method.fromNetty(nettyRequest.method());
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
        dealWithUnhandledException(request, response, cause);
    }

    @Override
    public void onConnectionEnded(ChannelHandlerContext ctx) {
        if (!response.outputState().endState()) {
            onCancelled(ResponseState.CLIENT_DISCONNECTED);
        }
    }


    static boolean dealWithUnhandledException(MuRequest request, MuResponse response, Throwable ex) {
        boolean forceDisconnect = response instanceof Http1Response;

        if (response.hasStartedSendingData()) {
            if (response.responseState() == ResponseState.CLIENT_DISCONNECTED) {
                log.debug("Client disconnected before " + request + " was complete");
            } else {
                log.info("Unhandled error from handler for " + request + " (note that a " + response.status() +
                    " was already sent to the client before the error occurred and so the client may receive an incomplete response)", ex);
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

}
