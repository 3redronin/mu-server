package io.muserver;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.text.ParseException;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
    private final Queue<HttpRequestTemp> requestPipeline = new ConcurrentLinkedQueue<>();
    // At most one active exchange exists on HTTP/1.1: either an HTTP request/response or a websocket takeover.
    private final AtomicReference<@Nullable ActiveExchange> activeExchange = new AtomicReference<>();
    // Lifecycle is cross-thread: connection loop + timeout thread + shutdown thread.
    private final AtomicReference<HttpConnectionState> state = new AtomicReference<>(HttpConnectionState.OPEN);

    private static final class ActiveExchange {
        @Nullable
        final Mu3Request request;
        @Nullable
        final BaseResponse response;
        @Nullable
        final WebsocketConnection websocket;

        private ActiveExchange(
            @Nullable Mu3Request request,
            @Nullable BaseResponse response,
            @Nullable WebsocketConnection websocket
        ) {
            this.request = request;
            this.response = response;
            this.websocket = websocket;
        }

        static ActiveExchange forRequest(
            Mu3Request request,
            BaseResponse response
        ) {
            return new ActiveExchange(request, response, null);
        }

        static ActiveExchange forWebsocket(WebsocketConnection websocket) {
            return new ActiveExchange(null, null, websocket);
        }
    }

    static final class ExchangeResult {
        final boolean closeConnection;
        final @Nullable WebsocketConnection websocket;

        ExchangeResult(boolean closeConnection, @Nullable WebsocketConnection websocket) {
            this.closeConnection = closeConnection;
            this.websocket = websocket;
        }
    }

    Http1Connection(Mu3ServerImpl server, ConnectionAcceptor creator, ConnectionTransport transport,
                    ConnectionAcceptedTime acceptedTime,
                    @Nullable ProxiedConnectionInfo proxyInfo, ExecutorService handlerExecutor) {
        super(server, creator, transport, acceptedTime, proxyInfo);
        this.handlerExecutor = handlerExecutor;
    }

    /**
     * A readiness owner calls advance and supplies input to the bounded buffer. It accounts for
     * input when admitting bytes; the body reader uses the same unwrapped buffer. Output retains
     * the usual successful-write accounting wrapper. Transport close/abort must be nonblocking.
     * The continuation only queues transport work; it must not block, throw, or reenter the driver.
     */
    ReadDriver readDriver(TransportInputBuffer input, OutputStream output, Runnable continuation) {
        return readDriver(input, output, continuation, null);
    }

    ReadDriver readDriver(TransportInputBuffer input, OutputStream output, Runnable continuation,
                          @Nullable TransportOutputBuffer upgradeOutput) {
        return new ReadDriver(input, output, continuation, upgradeOutput);
    }

    final class ReadDriver {
        private final TransportInputBuffer input;
        private final OutputStream output;
        private final Runnable continuation;
        private final @Nullable TransportOutputBuffer upgradeOutput;
        private final Http1MessageParser parser;
        private final Http1MessageParser.AvailableRead availableRead;
        private final CompletableFuture<Void> ended = new CompletableFuture<>();
        private @Nullable CompletableFuture<ExchangeResult> pending;
        private WebsocketConnection.@Nullable ReadDriver websocketReader;

        private ReadDriver(TransportInputBuffer input, OutputStream output, Runnable continuation,
                           @Nullable TransportOutputBuffer upgradeOutput) {
            this.input = input;
            this.output = output;
            this.continuation = continuation;
            this.upgradeOutput = upgradeOutput;
            var connectionInput = new HttpConnectionInputStream(Http1Connection.this, input, false);
            this.availableRead = bytes -> connectionInput.readAvailable(input, bytes);
            this.parser = new Http1MessageParser(HttpMessageType.REQUEST, requestPipeline,
                connectionInput, server.maxRequestHeadersSize(), server.maxUrlSize(), input, availableRead);
        }

        CompletableFuture<Void> completion() { return ended; }

        /** Called once by a failed owner after its last advance; no further parsing is possible. */
        void ownerFailed(IOException failure) {
            if (websocketReader != null) websocketReader.ownerFailed(failure);
            CompletableFuture<ExchangeResult> work = pending;
            if (work == null) end(failure);
            else work.whenComplete((ignored, error) -> end(failure));
        }

        /** Bounded header progression only. The caller limits advances per transport turn. */
        boolean advance() {
            if (ended.isDone()) return false;
            boolean progress = false;
            try {
                if (pending != null) {
                    if (websocketReader != null) websocketReader.inputAvailable();
                    if (!pending.isDone()) return false;
                    ExchangeResult result = pending.join();
                    pending = null;
                    progress = true;
                    if (result.closeConnection || closed.get() || state.get() != HttpConnectionState.OPEN) {
                        end(null);
                        return true;
                    }
                    WebsocketConnection websocket = result.websocket;
                    if (websocket != null) {
                        activateWebsocket(websocket);
                        WebsocketConnection.ReadDriver reader = websocket.readDriver(input, output, parser.takeInputForUpgrade(),
                            upgradeOutput == null ? null : upgradeOutput.asynchronousWriter());
                        websocketReader = reader;
                        pending = reader.completion().thenApply(ignored -> new ExchangeResult(true, null));
                        pending.whenComplete((ignored, failure) -> continuation.run());
                        reader.inputAvailable();
                        return true;
                    }
                }
                if (closed.get() || state.get() != HttpConnectionState.OPEN) { end(null); return true; }
                int buffered = input.available();
                Http1ConnectionMsg message;
                try { message = parser.readAvailable(availableRead); }
                catch (ParseException malformed) { message = parser.rejectInvalidRequest(malformed); }
                if (message == null) return progress || input.available() < buffered;
                if (MessageBodyBit.isEof(message)) {
                    markRemoteClosed();
                    transport.shutdownInput();
                    end(null);
                } else {
                    pending = executeExchange((HttpRequestTemp) message, parser, output);
                    pending.whenComplete((ignored, failure) -> continuation.run());
                }
                return true;
            } catch (Throwable failure) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
                end(cause);
                FatalErrors.rethrow(cause);
                return true;
            }
        }

        private void end(@Nullable Throwable failure) {
            pending = null;
            websocketReader = null;
            activeExchange.set(null);
            requestPipeline.clear();
            if (failure != null) forceTransportClose();
            closeTransportQuietly();
            if (failure == null) ended.complete(null);
            else ended.completeExceptionally(failure);
        }
    }

    private CompletableFuture<ExchangeResult> executeExchange(HttpRequestTemp request, Http1MessageReader reader,
                                                               OutputStream output) {
        var completion = new CompletableFuture<ExchangeResult>();
        try {
            // Submitting to a user-supplied handler executor can itself run application code.
            // Keep preparation, submission, rejection writes and body cleanup off the selector.
            server.executeInternalTask(() -> {
                if (closed.get()) {
                    completion.complete(new ExchangeResult(true, null));
                    return;
                }
                Exchange exchange;
                try { exchange = prepareExchange(request, reader, output); }
                catch (Throwable failure) {
                    completion.completeExceptionally(failure);
                    FatalErrors.rethrow(failure);
                    return;
                }
                try {
                    exchange.execute().whenComplete((accepted, failure) -> finishExchangeLater(exchange, accepted, failure, completion));
                } catch (Throwable failure) {
                    finishExchangeLater(exchange, null, failure, completion);
                    FatalErrors.rethrow(failure);
                }
            });
        } catch (RuntimeException | Error failure) {
            completion.completeExceptionally(failure);
            FatalErrors.rethrow(failure);
        }
        return completion;
    }

    private void finishExchangeLater(Exchange exchange, @Nullable Boolean accepted, @Nullable Throwable failure,
                                      CompletableFuture<ExchangeResult> completion) {
        finishExchangeLater(exchange, accepted, failure, completion, true);
    }

    private void finishExchangeLater(Exchange exchange, @Nullable Boolean accepted, @Nullable Throwable failure,
                                      CompletableFuture<ExchangeResult> completion, boolean prepareCleanup) {
        try {
            server.executeInternalTask(() -> {
                try {
                    if (prepareCleanup) {
                        CompletableFuture<Boolean> cleanup = exchange.startCleanup(accepted, failure);
                        if (cleanup != null && !cleanup.isDone()) {
                            cleanup.whenComplete((ignored, cleanupFailure) ->
                                finishExchangeLater(exchange, accepted, failure, completion, false));
                            return;
                        }
                    }
                    completion.complete(exchange.finish(accepted, failure));
                }
                catch (Throwable error) {
                    completion.completeExceptionally(error);
                    FatalErrors.rethrow(error);
                }
            });
        } catch (RuntimeException | Error rejected) {
            try { exchange.abandon(accepted); }
            finally { completion.completeExceptionally(rejected); }
            FatalErrors.rethrow(rejected);
        }
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
                } catch (ParseException malformedRequest) {
                    msg = requestParser.rejectInvalidRequest(malformedRequest);
                } catch (IOException e) {
                    break;
                }
                if (MessageBodyBit.isEof(msg)) {
//                    reqStream.closeQuietly() // TODO: confirm if the input stream should be closed
                    markRemoteClosed();
                    transport.shutdownInput();
                    break;
                }
                Exchange exchange = prepareExchange((HttpRequestTemp) msg, requestParser, outputStream);
                Boolean accepted = null;
                Throwable failure = null;
                try { accepted = exchange.execute().get(); }
                catch (Throwable error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    failure = error instanceof ExecutionException && error.getCause() != null ? error.getCause() : error;
                }
                ExchangeResult result = exchange.finish(accepted, failure);
                closeConnection = result.closeConnection;
                WebsocketConnection websocket = result.websocket;
                if (!closeConnection && websocket != null) {
                    activateWebsocket(websocket);
                    websocket.runAndBlockUntilDone(inputStream, outputStream, requestParser.takeInputForUpgrade());
                    closeConnection = true;
                }
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            // probably shouldn't log here so much for things like IO errors which would be common when clients disconnect
            log.error("Unhandled error at the socket", e);
        } finally {
            activeExchange.set(null);
            closeTransportQuietly();
        }
    }

    private Exchange prepareExchange(HttpRequestTemp request, Http1MessageReader requestParser,
                                     OutputStream outputStream) throws IOException {
        // The shared parser queues requests for response parsing. The server writes
        // responses directly, so consume the entry when we take ownership of the
        // request; otherwise every completed request lives as long as the connection.
        requestPipeline.remove(request);

        var rejectException = request.getRejectRequest();
        String relativeUrl;
        URI target;
        try {
            target = request.requestTarget();
            relativeUrl = Mutils.getRelativeUrl(target);
        } catch (HttpException e) {
            if (rejectException == null) {
                rejectException = e;
            }
            rejectException.responseHeaders().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
            target = null;
            relativeUrl = "/";
        }

        URI serverUri = creator.uri().resolve(relativeUrl);
        URI requestUri;
        try {
            URI defaultUri = target != null && target.isAbsolute()
                ? URI.create(target.getScheme() + "://" + target.getRawAuthority() + relativeUrl)
                : serverUri;
            requestUri = Headtils.getUri(log, request.headers(), relativeUrl, defaultUri);
        } catch (HttpException e) {
            if (rejectException == null) {
                rejectException = e;
            }
            // A rejected Expect: 100-continue request may never send its body,
            // including when an earlier rejection determined the response status.
            rejectException.responseHeaders().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
            requestUri = serverUri;
        }
        Method method = java.util.Objects.requireNonNull(request.getMethod(), "No HTTP method was parsed");
        if (rejectException == null) {
            try {
                QueryRequestValidation.validate(method, request.headers());
            } catch (HttpException e) {
                // The peer might be waiting for 100 Continue instead of sending its body.
                e.responseHeaders().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
                rejectException = e;
            }
        }
        HttpVersion httpVersion = java.util.Objects.requireNonNull(request.getHttpVersion(), "No HTTP version was parsed");
        BodySize bodySize = java.util.Objects.requireNonNull(request.getBodySize(), "No body size was parsed");
        InputStream requestBody = BodySize.NONE.equals(bodySize) ? EmptyInputStream.INSTANCE : new Http1BodyStream(requestParser, server.maxRequestBodySize());
        var muRequest = new Mu3Request(this, method, requestUri, serverUri, httpVersion, request.headers(), bodySize, requestBody);
        transport.readTimeoutMillis(requestTimeout);

        var muResponse = new Http1Response(muRequest, outputStream);
        muRequest.setResponse(muResponse);
        boolean closeConnection = muRequest.headers().closeConnectionRequested(httpVersion);

        return new Exchange(muRequest, muResponse, outputStream, closeConnection, rejectException);
    }

    /** One exchange owns the body reader until finish returns; the transport must not parse ahead. */
    private final class Exchange {
        final Mu3Request request;
        final Http1Response response;
        final OutputStream output;
        final boolean requestedClose;
        final @Nullable HttpException rejection;
        boolean started;
        private boolean invalidRequestRecorded;
        private @Nullable CompletableFuture<Boolean> completedCleanup;

        Exchange(Mu3Request request, Http1Response response, OutputStream output,
                 boolean requestedClose, @Nullable HttpException rejection) {
            this.request = request;
            this.response = response;
            this.output = output;
            this.requestedClose = requestedClose;
            this.rejection = rejection;
        }

        CompletableFuture<Boolean> execute() {
            if (rejection != null) return CompletableFuture.completedFuture(true);
            onRequestStarted(request);
            started = true;
            return handleExchangeOnHandlerExecutor(request, response);
        }

        @Nullable CompletableFuture<Boolean> startCleanup(@Nullable Boolean accepted, @Nullable Throwable failure) {
            recordRejection();
            // Preserve rejection paths whose peers may never send a body (notably Expect requests).
            if (failure != null || Boolean.FALSE.equals(accepted)
                || (rejection != null && rejection.responseHeaders().closeConnectionRequested(request.httpVersion()))) return null;
            try { completedCleanup = request.cleanupAsynchronously(rejection == null ? response.status() : rejection.status()); }
            catch (Throwable cleanupFailure) {
                completedCleanup = new CompletableFuture<>();
                completedCleanup.completeExceptionally(cleanupFailure);
            }
            return completedCleanup;
        }

        private void recordRejection() {
            if (rejection != null && !invalidRequestRecorded) {
                invalidRequestRecorded = true;
                onInvalidRequest(rejection);
            }
        }

        /** Retirement still releases admission when shutdown prevents scheduling normal cleanup. */
        void abandon(@Nullable Boolean accepted) {
            if (!started) return;
            response.setState(ResponseState.ERRORED);
            if (Boolean.FALSE.equals(accepted)) {
                rejectedDueToOverload.incrementAndGet();
                server.getStatsImpl().onRejectedDueToOverload();
                onApplicationRequestRejected(request);
            } else if (request.wasRateLimitRejected()) {
                onApplicationRequestRejected(request);
                server.onRequestRejected(rateLimitRejection(request));
            } else onExchangeEndedOnHandler(response);
        }

        ExchangeResult finish(@Nullable Boolean accepted, @Nullable Throwable failure) throws IOException {
            boolean closeConnection = requestedClose;
            if (rejection != null) {
                recordRejection();
                String rejectReason = rejection.getMessage() != null ? rejection.getMessage() : rejection.status().toString();
                var rejectedRequest = new RejectedRequestImpl(rejection.status().code(), rejectReason,
                    request.method().name(), request.uri().toString(), Http1Connection.this);
                try {
                    response.status(rejection.status());
                    response.headers().set(rejection.responseHeaders());
                    if (rejection.getMessage() != null) response.write(rejection.getMessage());
                    if (rejection.responseHeaders().closeConnectionRequested(request.httpVersion())) {
                        // An Expect peer may never send its rejected body.
                        response.cleanup();
                        closeConnection = true;
                    } else closeConnection = cleanUpNicely(closeConnection, response, request, completedCleanup);
                } finally { server.onRequestRejected(rejectedRequest); }
            } else {
                boolean rejectedByHandlerExecutor = failure == null && Boolean.FALSE.equals(accepted);
                try {
                    if (failure != null) throw failure;
                    closeConnection = rejectedByHandlerExecutor
                        ? rejectRequestDueToHandlerOverload(request, output)
                        : cleanUpNicely(closeConnection, response, request, completedCleanup);
                } catch (Throwable error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    FatalErrors.rethrow(error);
                    closeConnection = true;
                    log.warn("Unrecoverable error for " + request, error);
                    response.setState(ResponseState.ERRORED);
                } finally {
                    if (request.wasRateLimitRejected()) {
                        onApplicationRequestRejected(request);
                        server.onRequestRejected(rateLimitRejection(request));
                    } else if (!rejectedByHandlerExecutor) onExchangeEndedOnHandler(response);
                    transport.readTimeoutMillis(0);
                }
            }
            closeConnection |= state.get() != HttpConnectionState.OPEN || closed.get();
            return new ExchangeResult(closeConnection, response.getWebsocket());
        }
    }

    private void activateWebsocket(WebsocketConnection websocket) throws IOException {
        activeExchange.set(ActiveExchange.forWebsocket(websocket));
        transport.readTimeoutMillis(websocket.settings.idleReadTimeoutMillis);
    }

    /** Completes when application handling (including any async exchange) has ended, without waiting. */
    private CompletableFuture<Boolean> handleExchangeOnHandlerExecutor(Mu3Request request, Http1Response response) {
        if (!server.tryAdmit(request)) return CompletableFuture.completedFuture(false);
        CompletableFuture<Boolean> completion = new CompletableFuture<>();
        try {
            handlerExecutor.execute(server.handlerApplicationTask(() -> {
                try {
                    if (closed.get()) response.setState(ResponseState.CLIENT_DISCONNECTED);
                    CompletableFuture<@Nullable Void> async = response.responseState().endState() ? null : handleExchange(request, response);
                    if (async == null) completion.complete(true);
                    else async.whenComplete((ignored, error) -> {
                        Throwable failure = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        if (failure == null) completion.complete(true);
                        else if (failure instanceof Exception) {
                            try {
                                handleAsyncExceptionOnHandler(request, response, (Exception) failure)
                                    .whenComplete((handled, handlerFailure) -> {
                                        if (handlerFailure == null) completion.complete(true);
                                        else completion.completeExceptionally(handlerFailure);
                                    });
                            } catch (Throwable taskFailure) {
                                completion.completeExceptionally(taskFailure);
                                FatalErrors.rethrow(taskFailure);
                            }
                        } else completion.completeExceptionally(failure);
                    });
                } catch (Throwable error) {
                    completion.completeExceptionally(error);
                    FatalErrors.rethrow(error);
                }
            }));
        } catch (RejectedExecutionException rejected) {
            completion.complete(false);
        }
        return completion;
    }

    private CompletableFuture<@Nullable Void> handleAsyncExceptionOnHandler(Mu3Request request, Http1Response response,
                                                                          Exception failure) {
        CompletableFuture<@Nullable Void> handled = new CompletableFuture<>();
        Runnable task = () -> {
            try {
                handleExchangeException(request, response, failure);
                handled.complete(null);
            } catch (Throwable error) {
                handled.completeExceptionally(error);
                FatalErrors.rethrow(error);
            }
        };
        RejectedExecutionException rejected = server.tryExecuteHandlerTask(task);
        if (rejected != null) handled.completeExceptionally(rejected);
        return handled;
    }

    private void onExchangeEndedOnHandler(Http1Response response) {
        recordExchangeEnded(response);
        server.executeResponseCompletionTask(() -> notifyExchangeEnded(response));
    }

    private boolean rejectRequestDueToHandlerOverload(Mu3Request request, OutputStream outputStream) throws IOException {
        rejectedDueToOverload.incrementAndGet();
        server.getStatsImpl().onRejectedDueToOverload();
        onApplicationRequestRejected(request);

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

    private void onApplicationRequestRejected(Mu3Request request) {
        server.onRequestSubmissionRejected(request);
        activeExchange.updateAndGet(cur ->
            cur != null && isSameRequest(cur.request, request) ? null : cur
        );
    }

    private boolean cleanUpNicely(Boolean closeConnection, Http1Response muResponse, Mu3Request muRequest,
                                  @Nullable CompletableFuture<Boolean> completedCleanup) {
        var reallyClose = closeConnection;
        if (!reallyClose) {
            reallyClose = muResponse.headers().closeConnectionRequested(muRequest.httpVersion());
        }
        if (!reallyClose && muResponse.shouldCloseConnectionAfterResponse()) {
            reallyClose = true;
        }
        try {
            // The async result retains exceptions as well as false results: they historically
            // have different effects on completion of an already-started response.
            if (completedCleanup != null && !completedCleanup.isDone()) throw new IllegalStateException("Body cleanup is still pending");
            if (!(completedCleanup == null ? muRequest.cleanup() : cleanupResult(completedCleanup))) {
                reallyClose = true;
                if (!muRequest.completedSuccessfully() && muResponse.hasStartedSendingData()) {
                    // A malformed upload cannot turn an already-started response into
                    // a clean chunked/fixed-length completion.
                    muResponse.setState(ResponseState.ERRORED);
                    return true;
                }
            }
        } catch (Exception e) {
            reallyClose = true;
        }
        try {
            muResponse.cleanup();
        } catch (Exception e) {
            muResponse.setState(ResponseState.ERRORED);
            reallyClose = true;
        }
        return reallyClose;
    }

    private static boolean cleanupResult(CompletableFuture<Boolean> completed) {
        try { return completed.join(); }
        catch (CompletionException failure) {
            Throwable cause = failure;
            while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
            // A future must not turn a fatal/unchecked Error into the ordinary cleanup-exception path.
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }

    @Override
    public void onRequestStarted(Mu3Request req) {
        activeExchange.set(ActiveExchange.forRequest(
            req,
            req.responseForConnection()
        ));
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
            terminateActiveRequest(
                ResponseState.ERRORED,
                new MuException("Connection aborted")
            );
            state.set(HttpConnectionState.CLOSED);
            forceTransportClose();
            transport.close();
        } else {
            state.set(HttpConnectionState.CLOSED);
        }
    }

    @Override
    public void abortWithTimeout() throws IOException {
        if (closed.compareAndSet(false, true)) {
            terminateActiveRequest(
                ResponseState.TIMED_OUT,
                new TimeoutException("Idle timeout exceeded")
            );
            notifyWebsocketTimeout();
            state.set(HttpConnectionState.CLOSED);
            forceTransportClose();
            transport.close();
        } else {
            state.set(HttpConnectionState.CLOSED);
        }
    }


    @Override
    void initiateGracefulShutdown() {
        requestLocalShutdown();
        if (isIdle()) {
            forceShutdown();
        } else {
            var cur = activeExchange.get();
            if (cur != null && cur.websocket != null) {
                try {
                    cur.websocket.onServerShuttingDown();
                } catch (Exception e) {
                    forceShutdown();
                }
            }
        }
    }

    @Override
    void forceShutdown() {
        if (state.getAndSet(HttpConnectionState.CLOSED) != HttpConnectionState.CLOSED) {
            terminateActiveRequest(ResponseState.ERRORED, new IOException("Connection forcibly closed"));
        }
        forceTransportClose();
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

    // Begin physical closure while retaining the WebSocket exchange until its borrowed writes retire.
    void closeWebsocketTransport() {
        closeTransportQuietly();
    }

    private void closeTransportQuietly() {
        if (closed.compareAndSet(false, true)) {
            try {
                transport.close();
            } catch (IOException ignored) {
            } finally {
                state.set(HttpConnectionState.CLOSED);
            }
        } else {
            state.set(HttpConnectionState.CLOSED);
        }
    }

    private void terminateActiveRequest(
        ResponseState terminalState,
        Exception error
    ) {
        markActiveResponse(terminalState);
        var cur = activeExchange.get();
        if (cur != null && cur.request != null) {
            Mu3AsyncHandleImpl asyncHandle = cur.request.getAsyncHandle();
            if (asyncHandle != null) {
                asyncHandle.complete(error);
            }
        }
    }

    private void markActiveResponse(ResponseState terminalState) {
        var cur = activeExchange.get();
        if (cur != null && cur.response != null) {
            cur.response.setState(terminalState);
        }
    }

    @Override
    void onTransportInputEnd() {
        markActiveResponse(ResponseState.CLIENT_DISCONNECTED);
    }

    @Override
    void onTransportInputFailure(IOException failure) {
        // The HTTP/1 request deadline is implemented as a socket read timeout.
        // Leave that non-terminal so the parser can turn it into a 408 response;
        // connection-idle timeout is published by abortWithTimeout().
        if (!(failure instanceof java.net.SocketTimeoutException)) {
            markActiveResponse(ResponseState.CLIENT_DISCONNECTED);
        }
    }

    @Override
    void onTransportOutputFailure(IOException failure) {
        markActiveResponse(ResponseState.CLIENT_DISCONNECTED);
    }

    private void notifyWebsocketTimeout() {
        var cur = activeExchange.get();
        if (cur != null && cur.websocket != null) {
            cur.websocket.onTimeout();
        }
    }

}
