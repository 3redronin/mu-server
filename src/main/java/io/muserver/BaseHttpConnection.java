package io.muserver;

import org.jspecify.annotations.Nullable;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.StandardConstants;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

abstract class BaseHttpConnection implements HttpConnection {

    protected final Mu3ServerImpl server;
    protected final ConnectionAcceptor creator;
    protected final Socket clientSocket;
    @Nullable
    protected final Certificate clientCertificate;
    private final ConnectionAcceptedTime acceptedTime;
    private final long connectionReadyNanos = System.nanoTime();
    protected final InetSocketAddress remoteAddress;
    protected final InetSocketAddress localAddress;
    protected final AtomicLong lastIONanos = new AtomicLong(System.nanoTime());
    protected final AtomicLong completedRequests = new AtomicLong(0);
    protected final AtomicLong invalidHttpRequests = new AtomicLong(0);
    protected final AtomicLong rejectedDueToOverload = new AtomicLong(0);
    protected final AtomicBoolean closed = new AtomicBoolean(false);
    protected final int requestTimeout;

    BaseHttpConnection(
        Mu3ServerImpl server,
        ConnectionAcceptor creator,
        Socket clientSocket,
        @Nullable Certificate clientCertificate,
        ConnectionAcceptedTime acceptedTime
    ) {
        this.server = server;
        this.creator = creator;
        this.clientSocket = clientSocket;
        this.clientCertificate = clientCertificate;
        this.acceptedTime = acceptedTime;
        remoteAddress = (InetSocketAddress) clientSocket.getRemoteSocketAddress();
        localAddress = (InetSocketAddress) clientSocket.getLocalSocketAddress();
        requestTimeout = (int) Math.min(Integer.MAX_VALUE, server.requestIdleTimeoutMillis());
    }

    public abstract void start(InputStream clientIn, OutputStream clientOut) throws Throwable;

    protected void onInvalidRequest(HttpException rejectException) {
        if (rejectException.status().sameCode(HttpStatus.TOO_MANY_REQUESTS_429)) {
            rejectedDueToOverload.incrementAndGet();
            server.getStatsImpl().onRejectedDueToOverload();
        } else {
            invalidHttpRequests.incrementAndGet();
            server.getStatsImpl().onInvalidRequest();
        }
    }

    protected void onRequestStarted(Mu3Request req) {
        server.onRequestStarted(req);
    }

    protected RejectedRequest rateLimitRejection(Mu3Request request) {
        String reason = HttpStatus.TOO_MANY_REQUESTS_429.toString();
        return new RejectedRequestImpl(
            HttpStatus.TOO_MANY_REQUESTS_429.code(),
            reason,
            request.method().name(),
            request.uri().toString(),
            this
        );
    }

    protected @Nullable CompletableFuture<@Nullable Void> handleExchange(Mu3Request muRequest, BaseResponse muResponse) throws Throwable {
        try {
            if (applyRateLimits(muRequest, muResponse)) {
                return null;
            }
            var handled = false;
            for (var handler : server.handlers()) {
                if (handler.handle(muRequest, muResponse)) {
                    handled = true;
                    break;
                }
            }
            if (!handled) throw new HttpException(HttpStatus.NOT_FOUND_404, "This page is not available. Sorry about that.");

            if (muRequest.isAsync()) {
                var asyncHandle = java.util.Objects.requireNonNull(muRequest.getAsyncHandle());
                return asyncHandle.exchangeCompletion();
            }
        } catch (Exception e) {
            handleExchangeException(muRequest, muResponse, e);
        }
        return null;
    }

    private boolean applyRateLimits(
        Mu3Request request,
        BaseResponse response
    ) {
        RateLimiterImpl.Decision first = null;
        for (RateLimiterImpl rateLimiter : server.rateLimiters) {
            RateLimiterImpl.Decision decision = rateLimiter.record(request);
            if (decision != null && first == null) {
                first = decision;
            }
        }
        if (first == null
            || first.action() != RateLimitRejectionAction.SEND_429) {
            return false;
        }

        HttpException rejection =
            new HttpException(HttpStatus.TOO_MANY_REQUESTS_429);
        onInvalidRequest(rejection);
        request.onRateLimitRejected();
        response.status(rejection.status());
        String retryAfter = first.retryAfter();
        if (retryAfter != null) {
            response.headers().set(HeaderNames.RETRY_AFTER, retryAfter);
        }
        response.write(java.util.Objects.requireNonNull(rejection.getMessage()));
        return true;
    }

    protected void handleExchangeException(Mu3Request muRequest, BaseResponse muResponse, Exception failure) throws Throwable {
        if (muResponse.hasStartedSendingData()) {
            // can't write a custom error at this point
            throw failure;
        } else {
            server.exceptionHandler().handle(muRequest, muResponse, failure);
        }
    }

    protected void onExchangeEnded(ResponseInfo exchange) {
        recordExchangeEnded(exchange);
        notifyExchangeEnded(exchange);
    }

    protected void recordExchangeEnded(ResponseInfo exchange) {
        completedRequests.incrementAndGet();
        server.recordExchangeEnded(exchange);
    }

    protected void notifyExchangeEnded(ResponseInfo exchange) {
        BaseResponse resp = (BaseResponse) exchange.response();
        resp.notifyCompletionListeners(exchange);
        server.notifyExchangeEnded(exchange);
    }


    @Override
    public long idleTimeMillis() {
        long idleNanos = MonotonicTime.elapsedNanosSince(lastIONanos.get());
        return idleNanos <= 0L
            ? 0L
            : java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(idleNanos);
    }
    @Override
    public boolean isHttps() {
        return creator.isHttps();
    }
    @Override
    public @Nullable String httpsProtocol() {
        if (clientSocket instanceof SSLSocket) {
            return ((SSLSocket)clientSocket).getSession().getProtocol();
        } else {
            return null;
        }
    }
    @Override
    public @Nullable String cipher() {
        if (clientSocket instanceof SSLSocket) {
            return ((SSLSocket)clientSocket).getSession().getCipherSuite();
        } else {
            return null;
        }
    }

    @Override
    public Instant startTime() {
        return acceptedTime.instant();
    }

    @Override
    public long handshakeDurationMillis() {
        return acceptedTime.elapsedMillisUntil(connectionReadyNanos);
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    @Override
    public long completedRequests() {
        return completedRequests.get();
    }

    @Override
    public long invalidHttpRequests() {
        return invalidHttpRequests.get();
    }

    @Override
    public long rejectedDueToOverload() {
        return rejectedDueToOverload.get();
    }

    @Override
    public MuServer server() { return server; }

    Mu3ServerImpl serverImpl() { return server; }

    @Override
    public Optional<Certificate> clientCertificate() {
        return Optional.ofNullable(clientCertificate);
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public boolean isIdle() {
        return activeRequests().isEmpty() && activeWebsockets().isEmpty();
    }

    void onBytesRead(int read) {
        onIO();
        server.getStatsImpl().onBytesRead(read);
    }

    void onBytesSent(int sent) {
        onIO();
        server.getStatsImpl().onBytesSent(sent);
    }

    void onTransportInputEnd() {
    }

    void onTransportInputFailure(IOException failure) {
    }

    void onTransportOutputFailure(IOException failure) {
    }

    private void onIO() {
        MonotonicTime.publishLatest(lastIONanos, System.nanoTime());
    }

    boolean hasBeenIdleFor(long nowNanos, long timeoutNanos) {
        return nowNanos - lastIONanos.get() >= timeoutNanos;
    }

    @Override
    public String toString() {
        return httpVersion().version() + " connection from " + remoteAddress + " to " + localAddress;
    }

    public abstract void abortWithTimeout() throws IOException;
    
    abstract void initiateGracefulShutdown() throws IOException;
    abstract void forceShutdown();

    @Override
    public Optional<String> sniHostName() {
        if (!(clientSocket instanceof SSLSocket)) {
            return Optional.empty();
        }
        SSLSession session = ((SSLSocket) clientSocket).getSession();
        if (!(session instanceof ExtendedSSLSession)) {
            return Optional.empty();
        }
        for (SNIServerName serverName : ((ExtendedSSLSession) session).getRequestedServerNames()) {
            if (serverName.getType() == StandardConstants.SNI_HOST_NAME) {
                return Optional.of(((SNIHostName) serverName).getAsciiName());
            }
        }
        return Optional.empty();
    }
}
