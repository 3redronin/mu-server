package io.muserver;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Collections.emptySet;

class Http1Connection extends BaseHttpConnection {

    private static final Logger log = LoggerFactory.getLogger(Http1Connection.class);
    private final ExecutorService handlerExecutor;
    private final boolean handlersRunOnConnectionTask;
    private final Queue<HttpRequestTemp> requestPipeline = new ConcurrentLinkedQueue<>();
    // At most one active exchange exists on HTTP/1.1: either an HTTP request/response or a websocket takeover.
    private final AtomicReference<@Nullable ActiveExchange> activeExchange = new AtomicReference<>();
    // Lifecycle is cross-thread: connection loop + timeout thread + shutdown thread.
    private final AtomicReference<HttpConnectionState> state = new AtomicReference<>(HttpConnectionState.OPEN);

    private static final class ActiveExchange {
        @Nullable
        final Mu3Request request;
        @Nullable
        final WebsocketConnection websocket;

        private ActiveExchange(@Nullable Mu3Request request, @Nullable WebsocketConnection websocket) {
            this.request = request;
            this.websocket = websocket;
        }

        static ActiveExchange forRequest(Mu3Request request) {
            return new ActiveExchange(request, null);
        }

        static ActiveExchange forWebsocket(WebsocketConnection websocket) {
            return new ActiveExchange(null, websocket);
        }
    }

    private static final class HandlerExecution {
        private final boolean accepted;
        private final @Nullable CompletableFuture<@Nullable Void> asyncCompletion;

        private HandlerExecution(boolean accepted, @Nullable CompletableFuture<@Nullable Void> asyncCompletion) {
            this.accepted = accepted;
            this.asyncCompletion = asyncCompletion;
        }

        private static HandlerExecution accepted(@Nullable CompletableFuture<@Nullable Void> asyncCompletion) {
            return new HandlerExecution(true, asyncCompletion);
        }

        private static HandlerExecution rejected() {
            return new HandlerExecution(false, null);
        }
    }

    Http1Connection(Mu3ServerImpl server, ConnectionAcceptor creator, Socket clientSocket,
                    @Nullable Certificate clientCertificate, Instant handshakeStartTime,
                    ExecutorService handlerExecutor, boolean handlersRunOnConnectionTask) {
        super(server, creator, clientSocket, clientCertificate, handshakeStartTime);
        this.handlerExecutor = handlerExecutor;
        this.handlersRunOnConnectionTask = handlersRunOnConnectionTask;
    }

    @Override
    public void start(InputStream inputStream, OutputStream outputStream) {

        try {
            var requestParser = new Http1MessageParser(
                HttpMessageType.REQUEST,
                requestPipeline,
                inputStream,
                server.maxRequestHeadersSize(),
                server.maxUrlSize()
            );
            var closeConnection = false;
            while (!closeConnection) {
                Http1ConnectionMsg msg;
                try {
                    msg = requestParser.readNext();
                } catch (SocketTimeoutException ste) {
                    throw HttpException.requestTimeout();
                } catch (IOException e) {
                    log.info("Error reading from client input stream " + e.getClass() + " " + e.getMessage());
                    break;
                }
                if (MessageBodyBit.isEof(msg)) {
                    log.info("EOF detected");
//                    reqStream.closeQuietly() // TODO: confirm if the input stream should be closed
                    markRemoteClosed();
                    clientSocket.shutdownInput();
                    break;
                }
                var request = (HttpRequestTemp)msg;

                var rejectException = request.getRejectRequest();
                String relativeUrl;
                try {
                    relativeUrl = request.normalisedUri();
                } catch (HttpException e) {
                    if (rejectException == null) {
                        rejectException = e;
                    }
                    relativeUrl = "/";
                }

                URI serverUri = creator.uri().resolve(relativeUrl);
                URI requestUri = Headtils.getUri(log, request.headers(), relativeUrl, serverUri);
                Method method = java.util.Objects.requireNonNull(request.getMethod(), "No HTTP method was parsed");
                HttpVersion httpVersion = java.util.Objects.requireNonNull(request.getHttpVersion(), "No HTTP version was parsed");
                BodySize bodySize = java.util.Objects.requireNonNull(request.getBodySize(), "No body size was parsed");
                InputStream requestBody = BodySize.NONE.equals(bodySize) ? EmptyInputStream.INSTANCE : new Http1BodyStream(requestParser, server.maxRequestBodySize());
                var muRequest = new Mu3Request(this, method, requestUri, serverUri, httpVersion, request.headers(), bodySize, requestBody);
                clientSocket.setSoTimeout(requestTimeout);

                var muResponse = new Http1Response(muRequest, outputStream);
                muRequest.setResponse(muResponse);
                closeConnection = muRequest.headers().closeConnectionRequested(httpVersion);

                if (rejectException == null) {
                    RateLimitRejectionAction first = null;
                    for (RateLimiterImpl rateLimiter : server.rateLimiters) {
                        var action = rateLimiter.record(muRequest);
                        if (action != null && first == null) {
                            first = action;
                        }
                    }
                    if (first != null) {
                        if (first == RateLimitRejectionAction.SEND_429) {
                            rejectException = new HttpException(HttpStatus.TOO_MANY_REQUESTS_429);
                            rejectException.responseHeaders().setAll(request.headers());
                        }
                    }
                }

                if (rejectException != null) {
                    onInvalidRequest(rejectException);
                    String rejectedMethod = method.name();
                    String rejectReason = rejectException.getMessage() != null ? rejectException.getMessage() : rejectException.status().toString();
                    server.onRequestRejected(new RejectedRequestImpl(rejectException.status().code(),
                        rejectReason, rejectedMethod, requestUri.toString(), this));
                    muResponse.status(rejectException.status());
                    muResponse.headers().set(rejectException.responseHeaders());
                    if (rejectException.getMessage() != null) {
                        muResponse.write(rejectException.getMessage());
                    }
                    closeConnection = cleanUpNicely(closeConnection, muResponse, muRequest);
                } else {

                    onRequestStarted(muRequest);

                    boolean rejectedByHandlerExecutor = false;
                    try {
                        HandlerExecution execution = handleExchangeOnHandlerExecutor(muRequest, muResponse);
                        if (execution.accepted) {
                            CompletableFuture<@Nullable Void> asyncCompletion = execution.asyncCompletion;
                            if (asyncCompletion != null) {
                                awaitAsyncCompletion(asyncCompletion, muRequest, muResponse);
                            }
                            closeConnection = cleanUpNicely(closeConnection, muResponse, muRequest);
                        } else {
                            rejectedByHandlerExecutor = true;
                            closeConnection = rejectRequestDueToHandlerOverload(muRequest, outputStream);
                        }
                    } catch (Throwable e) {
                        closeConnection = true;
                        log.warn("Unrecoverable error for " + muRequest, e);
                        muResponse.setState(ResponseState.ERRORED);
                    } finally {
                        if (!rejectedByHandlerExecutor) {
                            onExchangeEndedOnHandler(muResponse);
                        }
                        clientSocket.setSoTimeout(0);
                    }
                    var websocket = muResponse.getWebsocket();
                    if (!closeConnection && websocket != null) {
                        activeExchange.set(ActiveExchange.forWebsocket(websocket));
                        clientSocket.setSoTimeout(websocket.settings.idleReadTimeoutMillis);
                        websocket.runAndBlockUntilDone(inputStream, outputStream, requestParser.readBuffer);
                        closeConnection = true;
                    }
                }
                closeConnection = closeConnection || state.get() != HttpConnectionState.OPEN || closed.get();
            }
        } catch (Exception e) {
            // probably shouldn't log here so much for things like IO errors which would be common when clients disconnect
            log.error("Unhandled error at the socket", e);
        } finally {
            activeExchange.set(null);
            closeTransportQuietly();
        }
    }

    private HandlerExecution handleExchangeOnHandlerExecutor(Mu3Request request, Http1Response response) throws Throwable {
        if (handlersRunOnConnectionTask) {
            return HandlerExecution.accepted(handleExchange(request, response));
        }
        CompletableFuture<@Nullable CompletableFuture<@Nullable Void>> completion = new CompletableFuture<>();
        try {
            handlerExecutor.execute(() -> {
                try {
                    completion.complete(handleExchange(request, response));
                } catch (Throwable t) {
                    completion.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException rejected) {
            return HandlerExecution.rejected();
        }
        try {
            return HandlerExecution.accepted(completion.get());
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }

    private void awaitAsyncCompletion(CompletableFuture<@Nullable Void> completion, Mu3Request request,
                                      Http1Response response) throws Throwable {
        try {
            completion.get();
        } catch (ExecutionException e) {
            Throwable failure = e.getCause();
            if (!(failure instanceof Exception)) {
                throw failure;
            }
            handleAsyncExceptionOnHandler(request, response, (Exception) failure);
        }
    }

    private void handleAsyncExceptionOnHandler(Mu3Request request, Http1Response response,
                                               Exception failure) throws Throwable {
        if (handlersRunOnConnectionTask) {
            handleExchangeException(request, response, failure);
            return;
        }
        CompletableFuture<@Nullable Void> handled = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                handleExchangeException(request, response, failure);
                handled.complete(null);
            } catch (Throwable t) {
                handled.completeExceptionally(t);
            }
        };
        try {
            handlerExecutor.execute(task);
        } catch (RejectedExecutionException rejected) {
            task.run();
        }
        try {
            handled.get();
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }

    private void onExchangeEndedOnHandler(Http1Response response) {
        recordExchangeEnded(response);
        if (handlersRunOnConnectionTask) {
            notifyExchangeEnded(response);
            return;
        }
        Runnable task = () -> notifyExchangeEnded(response);
        try {
            handlerExecutor.execute(task);
        } catch (RejectedExecutionException rejected) {
            // Internal exchange accounting is already complete. Preserve application
            // notifications during shutdown or a transient capacity rejection.
            task.run();
        }
    }

    private boolean rejectRequestDueToHandlerOverload(Mu3Request request, OutputStream outputStream) throws IOException {
        rejectedDueToOverload.incrementAndGet();
        server.getStatsImpl().onRejectedDueToOverload();
        server.onRequestSubmissionRejected(request);
        activeExchange.updateAndGet(cur ->
            cur != null && isSameRequest(cur.request, request) ? null : cur
        );

        String rejectionReason = "503 Service Unavailable";
        outputStream.write(ConnectionAcceptor.serverUnavailableResponse);
        outputStream.flush();
        server.onRequestRejected(new RejectedRequestImpl(
            HttpStatus.SERVICE_UNAVAILABLE_503.code(),
            rejectionReason,
            request.method().name(),
            request.uri().toString(),
            this
        ));
        return true;
    }

    private boolean cleanUpNicely(Boolean closeConnection, Http1Response muResponse, Mu3Request muRequest) {
        var reallyClose = closeConnection;
        if (!reallyClose) {
            reallyClose = muResponse.headers().closeConnectionRequested(muRequest.httpVersion());
        }
        if (!reallyClose && muResponse.shouldCloseConnectionAfterResponse()) {
            reallyClose = true;
        }
        try {
            if (!muRequest.cleanup()) {
                reallyClose = true;
            }
        } catch (Exception e) {
            reallyClose = true;
        }
        try {
            muResponse.cleanup();
        } catch (Exception e) {
            reallyClose = true;
        }
        return reallyClose;
    }

    @Override
    public void onRequestStarted(Mu3Request req) {
        activeExchange.set(ActiveExchange.forRequest(req));
        super.onRequestStarted(req);
    }

    @Override
    protected void recordExchangeEnded(ResponseInfo exchange) {
        activeExchange.updateAndGet(cur -> cur != null && isSameRequest(cur.request, exchange.request()) ? null : cur);
        super.recordExchangeEnded(exchange);
    }

    @SuppressWarnings("ReferenceEquality") // Connection ownership belongs to the exact request instance.
    private static boolean isSameRequest(@Nullable Mu3Request current, MuRequest completed) {
        return current == completed;
    }


    @Override
    public HttpVersion httpVersion() {
        return HttpVersion.HTTP_1_1;
    }

    @Override
    public Set<MuRequest> activeRequests() {
        var cur = activeExchange.get();
        return cur != null && cur.request != null ? Set.of(cur.request) : emptySet();
    }

    @Override
    public Set<MuWebSocket> activeWebsockets() {
        var cur = activeExchange.get();
        return cur != null && cur.websocket != null ? Set.of(cur.websocket.webSocket()) : emptySet();
    }

    @Override
    public void abort() throws IOException {
        if (closed.compareAndSet(false, true)) {
            completeAsyncRequest(new MuException("Connection aborted"));
            state.set(HttpConnectionState.CLOSED);
            clientSocket.close();
        } else {
            state.set(HttpConnectionState.CLOSED);
        }
    }

    @Override
    public void abortWithTimeout() throws IOException {
        if (closed.compareAndSet(false, true)) {
            completeAsyncRequest(new TimeoutException("Idle timeout exceeded"));
            notifyWebsocketTimeout();
            state.set(HttpConnectionState.CLOSED);
            clientSocket.close();
        } else {
            state.set(HttpConnectionState.CLOSED);
        }
    }


    @Override
    void initiateGracefulShutdown() {
        requestLocalShutdown();
        if (isIdle()) {
            log.info("Connection is idle; shutting down");
            forceShutdown();
        } else if (!activeWebsockets().isEmpty()) {
            for (MuWebSocket activeWebsocket : activeWebsockets()) {
                try {
                    activeWebsocket.onServerShuttingDown();
                } catch (Exception e) {
                    log.info("Error while aborting websocket: {}", e.getMessage());
                    forceShutdown();
                }
            }
        }
    }

    @Override
    void forceShutdown() {
        state.set(HttpConnectionState.CLOSED);
        closeTransportQuietly();
    }

    private void requestLocalShutdown() {
        state.compareAndSet(HttpConnectionState.OPEN, HttpConnectionState.CLOSED_LOCAL);
    }

    private void markRemoteClosed() {
        if (state.compareAndSet(HttpConnectionState.OPEN, HttpConnectionState.CLOSED_REMOTE)) {
            return;
        }
        if (state.get() == HttpConnectionState.CLOSED_LOCAL) {
            state.compareAndSet(HttpConnectionState.CLOSED_LOCAL, HttpConnectionState.CLOSED);
        }
    }

    private void closeTransportQuietly() {
        if (closed.compareAndSet(false, true)) {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            } finally {
                state.set(HttpConnectionState.CLOSED);
            }
        } else {
            state.set(HttpConnectionState.CLOSED);
        }
    }

    private void completeAsyncRequest(Exception error) {
        var cur = activeExchange.get();
        if (cur != null && cur.request != null) {
            Mu3AsyncHandleImpl asyncHandle = cur.request.getAsyncHandle();
            if (asyncHandle != null) {
                asyncHandle.complete(error);
            }
        }
    }

    private void notifyWebsocketTimeout() {
        var cur = activeExchange.get();
        if (cur != null && cur.websocket != null) {
            cur.websocket.onTimeout();
        }
    }
}
