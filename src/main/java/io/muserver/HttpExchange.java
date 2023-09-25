package io.muserver;

import io.muserver.rest.MuRuntimeDelegate;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.TooLongFrameException;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Response;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

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
    final ChannelHandlerContext ctx;
    final NettyRequestAdapter request;
    final NettyResponseAdaptor response;
    /**
     * The HTTP2 stream ID, or -1 for HTTP1
     */
    private final int streamId;
    private final HttpConnection connection;
    private final long startTime = System.currentTimeMillis();
    private volatile long endTime;
    private volatile HttpExchangeState state = HttpExchangeState.IN_PROGRESS;
    private final List<HttpExchangeStateChangeListener> listeners = new CopyOnWriteArrayList<>();
    private ScheduledFuture<?> readTimer;

    boolean inLoop() {
        return ctx.executor().inEventLoop();
    }

    void block(Runnable runnable) {
        // TODO: only use the callable version as this perhaps doesn't block until the runnable is finished? (e.g. when doing a write)
        assert !inLoop() : "Should not be blocking on the event loop";
        io.netty.util.concurrent.Future<?> task = ctx.executor().submit(runnable);
        try {
            task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new InterruptedIOException("Interrupted while writing"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            } else {
                throw new MuException("Error while writing response", cause);
            }
        }
    }

    void block(Callable<ChannelFuture> callable) {
        assert !inLoop() : "Should not be blocking on the event loop";
        io.netty.util.concurrent.Future<ChannelFuture> task = ctx.executor().submit(callable);
        try {
            task.get().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new InterruptedIOException("Interrupted while writing"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            } else {
                throw new MuException("Error while writing response", cause);
            }
        }
    }

    HttpExchange(HttpConnection connection, ChannelHandlerContext ctx, NettyRequestAdapter request, NettyResponseAdaptor response, int streamId) {
        this.connection = connection;
        this.ctx = ctx;
        this.request = request;
        this.response = response;
        this.streamId = streamId;
        request.addChangeListener((exchange, newState) -> onReqOrRespStateChange(newState, null));
        response.addChangeListener((exchange, newState) -> onReqOrRespStateChange(null, newState));
    }

    void addChangeListener(HttpExchangeStateChangeListener listener) {
        this.listeners.add(listener);
    }

    private void onReqOrRespStateChange(RequestState requestChanged, ResponseState responseChanged) {
        RequestState reqState = request.requestState();
        ResponseState respState = response.responseState();
        if (reqState.endState() && respState == ResponseState.UPGRADED) {
            onEnded(HttpExchangeState.UPGRADED);
        } else if (reqState.endState() && respState.endState()) {
            HttpExchangeState newState = reqState == RequestState.ERRORED || !respState.completedSuccessfully() ? HttpExchangeState.ERRORED : HttpExchangeState.COMPLETE;
            onEnded(newState);
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

    public void complete() {
        assert inLoop() : "Not in NIO event loop";
        if (!response.outputState().endState()) {
            response.complete();
        } else {
            log.debug("Complete called twice for " + request);
        }
    }

    void onCancelled(ResponseState reason) {
        cancelReadTimeout();
        if (!response.outputState().endState()) {
            response.onCancelled(reason);
            request.onCancelled(reason, new MuException("Cancelled: " + reason.name()));
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
        return state.endState() && state != HttpExchangeState.ERRORED && response.outputState().completedSuccessfully();
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
    public void onMessage(ChannelHandlerContext ctx, Object msg, DoneCallback doneCallback) throws UnexpectedMessageException {
        if (!(msg instanceof HttpContent)) {
            throw new UnexpectedMessageException(this, msg);
        }
        cancelReadTimeout();
        HttpContent content = (HttpContent) msg;
        ByteBuf byteBuf = content.content().retain();
        boolean last = msg instanceof LastHttpContent;

        DoneCallback onDone = error -> {
            byteBuf.release();
            try {
                Runnable cleanup = () -> {
                    boolean requestInProgress = !request.requestState().endState();
                    if (error == null) {
                        if (requestInProgress) {
                            if (last) {
                                request.setState(RequestState.COMPLETE);
                            } else {
                                scheduleReadTimeout();
                            }
                        }
                    } else if (requestInProgress) {
                        request.onCancelled(ResponseState.ERRORED, error);
                    }
                };
                if (ctx.executor().inEventLoop()) {
                    cleanup.run();
                } else {
                    ctx.executor().execute(cleanup);
                }
            } finally {
                doneCallback.onComplete(error);
            }
        };
        try {
            request.onRequestBodyRead(byteBuf, last, onDone);
        } catch (Exception e) {
            try {
                onDone.onComplete(e);
            } catch (Exception exception) {
                log.error("Unhandled callback error", exception);
            }
        }
    }

    void scheduleReadTimeout() {
        cancelReadTimeout();
        long delay = connection.server().requestIdleTimeoutMillis();
        this.readTimer = ctx.executor().schedule(request::onReadTimeout, delay, TimeUnit.MILLISECONDS);
    }

    private void cancelReadTimeout() {
        ScheduledFuture<?> rt = this.readTimer;
        if (rt != null) {
            this.readTimer = null;
            rt.cancel(false);
        }
    }

    @Override
    public void onIdleTimeout(ChannelHandlerContext ctx, IdleStateEvent ise) {
        if (ise.state() == IdleState.ALL_IDLE) {
            onCancelled(ResponseState.TIMED_OUT);
            log.info("Closed " + request + " (from " + request.remoteAddress() + ") because the idle timeout specified in MuServerBuilder#withIdleTimeout is exceeded.");
        }
    }

    @Override
    public HttpConnection connection() {
        return connection;
    }

    @Override
    public void onUpgradeComplete(ChannelHandlerContext ctx) {
        throw new UnsupportedOperationException("Cannot upgrade to an HttpExchange");
    }

    public HttpExchangeState state() {
        return state;
    }

    static HttpExchange create(MuServerImpl server, String proto, ChannelHandlerContext ctx, Http1Connection connection,
                               HttpRequest nettyRequest, NettyHandlerAdapter nettyHandlerAdapter, MuStatsImpl connectionStats,
                               RequestStateChangeListener requestStateChangeListener, HttpExchangeStateChangeListener stateChangeListener) throws InvalidRequestException, RedirectException {
        ServerSettings settings = server.settings();
        throwIfInvalid(settings, ctx, nettyRequest);

        Method method = getMethod(nettyRequest.method());
        Http1Headers headers = new Http1Headers(nettyRequest.headers());

        String relativeUri = getRelativeUrl(nettyRequest.uri());

        NettyRequestAdapter muRequest = new NettyRequestAdapter(ctx, nettyRequest, headers, method,
            proto, relativeUri, headers.get(HeaderNames.HOST));

        MuStatsImpl serverStats = server.stats;
        Http1Response muResponse = new Http1Response(ctx, muRequest, new Http1Headers());

        HttpExchange httpExchange = new HttpExchange(connection, ctx, muRequest, muResponse, -1);
        muRequest.setExchange(httpExchange);
        muResponse.setExchange(httpExchange);

        if (settings.block(muRequest)) {
            throw new InvalidRequestException(HttpStatusCode.TOO_MANY_REQUESTS_429, "Please wait before sending more requests", "Rate limited");
        }
        httpExchange.addChangeListener(stateChangeListener);
        muRequest.addChangeListener(requestStateChangeListener);

        try {
            serverStats.onRequestStarted(httpExchange.request);
            connectionStats.onRequestStarted(httpExchange.request);
            nettyHandlerAdapter.onHeaders(httpExchange);
        } catch (RejectedExecutionException e) {
            serverStats.onRequestEnded(httpExchange.request);
            connectionStats.onRequestEnded(httpExchange.request);
            log.warn("Could not service " + muRequest + " because the thread pool is full so sending a 503");
            throw new InvalidRequestException(HttpStatusCode.SERVICE_UNAVAILABLE_503, "Try again later", "Rejected execution");
        }
        return httpExchange;
    }

    static String getRelativeUrl(String nettyUri) throws InvalidRequestException, RedirectException {
        try {
            URI requestUri = new URI(nettyUri).normalize();
            if (requestUri.getScheme() == null && requestUri.getHost() != null) {
                throw new RedirectException(new URI(nettyUri.substring(1)).normalize());
            }

            String s = requestUri.getRawPath();
            if (Mutils.nullOrEmpty(s)) {
                s = "/";
            } else {
                // TODO: consider a redirect if the URL is changed? Handle other percent-encoded characters?
                s = s.replace("%7E", "~")
                    .replace("%5F", "_")
                    .replace("%2E", ".")
                    .replace("%2D", "-")
                ;
            }
            String q = requestUri.getRawQuery();
            if (q != null) {
                s += "?" + q;
            }
            return s;
        } catch (RedirectException re) {
            throw re;
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("Invalid request URL " + nettyUri);
            throw new  InvalidRequestException(HttpStatusCode.BAD_REQUEST_400, "400 Bad Request", "Bad Request");
        }
    }

    static Method getMethod(HttpMethod nettyMethod) throws  InvalidRequestException {
        Method method;
        try {
            method = Method.fromNetty(nettyMethod);
        } catch (IllegalArgumentException e) {
            throw new  InvalidRequestException(HttpStatusCode.METHOD_NOT_ALLOWED_405, "405 Method Not Allowed", "Method Not Allowed");
        }
        return method;
    }

    private static void throwIfInvalid(ServerSettings settings, ChannelHandlerContext ctx, HttpRequest nettyRequest) throws  InvalidRequestException {
        if (nettyRequest.decoderResult().isFailure()) {
            Throwable cause = nettyRequest.decoderResult().cause();
            if (cause instanceof TooLongFrameException) {
                if (cause.getMessage().contains("header is larger")) {
                    throw new InvalidRequestException(HttpStatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE_431, "431 Request Header Fields Too Large", "Request Header Fields Too Large");
                } else if (cause.getMessage().contains("line is larger")) {
                    throw new InvalidRequestException(HttpStatusCode.URI_TOO_LONG_414, "414 Request-URI Too Long", "Request-URI Too Long");
                }
            }
            if (log.isDebugEnabled()) log.debug("Invalid http request received", cause);
            throw new InvalidRequestException(HttpStatusCode.INTERNAL_SERVER_ERROR_500, "Invalid HTTP request received", "Server Error");
        }

        String contentLenDecl = nettyRequest.headers().get("Content-Length");
        if (HttpUtil.is100ContinueExpected(nettyRequest)) {
            long requestBodyLen = contentLenDecl == null ? -1L : Long.parseLong(contentLenDecl, 10);
            if (requestBodyLen <= settings.maxRequestSize) {
                ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.CONTINUE));
            } else {
                throw new  InvalidRequestException(HttpStatusCode.EXPECTATION_FAILED_417, "417 Expectation Failed - request too large", "Expectation Failed");
            }
        }

        if (!nettyRequest.headers().contains(HttpHeaderNames.HOST)) {
            throw new  InvalidRequestException(HttpStatusCode.BAD_REQUEST_400, "400 Bad Request - no Host header", "Bad Request");
        }
        if (contentLenDecl != null) {
            long cld = Long.parseLong(contentLenDecl, 10);
            if (cld > settings.maxRequestSize) {
                throw new  InvalidRequestException(HttpStatusCode.CONTENT_TOO_LARGE_413, "413 Payload Too Large", "Payload Too Large");
            }
        }
    }

    void fireException(Throwable cause) {
        ctx.pipeline().fireUserEventTriggered(new MuExceptionFiredEvent(this, streamId, cause));
    }

    @Override
    public boolean onException(ChannelHandlerContext ctx, Throwable cause) {
        assert inLoop() : "onException not called from nio event loop";

        if (state.endState()) {
            log.warn("Got exception after state is " + state);
            return true;
        }

        boolean streamUnrecoverable = true;
        try {

            if (!response.hasStartedSendingData()) {
                if (request.requestState() != RequestState.ERRORED) {
                    streamUnrecoverable = false;
                }
                WebApplicationException wae;
                if (cause instanceof WebApplicationException) {
                    wae = (WebApplicationException) cause;
                } else {
                    String errorID = "ERR-" + UUID.randomUUID();
                    log.info("Sending a 500 to the client with ErrorID=" + errorID + " for " + request, cause);
                    wae = new InternalServerErrorException("Oops! An unexpected error occurred. The ErrorID=" + errorID);
                }
                Response exResp = wae.getResponse();
                if (exResp == null) {
                    exResp = Response.serverError().build();
                }
                int status = exResp.getStatus();
                if (status == 429 || status == 408 || status == 413) {
                    streamUnrecoverable = true;
                }
                response.status(status);
                boolean isHttp1 = request.protocol().equals("HTTP/1.1");
                MuRuntimeDelegate.writeResponseHeaders(request.uri(), exResp, response, isHttp1);
                if (streamUnrecoverable && isHttp1) {
                    response.headers().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
                }
                response.contentType(ContentTypes.TEXT_HTML_UTF8);
                String message = wae.getMessage();
                message = exceptionMessageMap.getOrDefault(message, message);
                response.writeOnLoop("<h1>" + status + " " + exResp.getStatusInfo().getReasonPhrase() + "</h1><p>" +
                    Mutils.htmlEncode(message) + "</p>")
                    .addListener(f -> {
                        ResponseState state = f.isSuccess() ? ResponseState.FULL_SENT : ResponseState.ERRORED;
                        response.outputState(f, state);
                    });
            } else {
                log.info(cause.getClass().getName() + " while handling " + request + " - note a " + response.status +
                    " was already sent and the client may have received an incomplete response. Exception was " + cause.getMessage());
            }
        } catch (Exception e) {
            log.warn("Error while processing processing " + cause + " for " + request, e);
        } finally {
            if (streamUnrecoverable) {
                response.onCancelled(ResponseState.ERRORED);
                request.onCancelled(ResponseState.ERRORED, cause);
            }
        }
        return streamUnrecoverable;
    }

    @Override
    public void onConnectionEnded(ChannelHandlerContext ctx) {
        if (!response.outputState().endState()) {
            onCancelled(ResponseState.CLIENT_DISCONNECTED);
        }
        if (!request.requestState().endState()) {
            request.onCancelled(ResponseState.CLIENT_DISCONNECTED, new ClientDisconnectedException());
        }
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