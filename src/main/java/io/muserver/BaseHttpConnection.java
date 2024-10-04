package io.muserver;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.SSLSocket;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

abstract class BaseHttpConnection implements HttpConnection {

    @NotNull
    protected final Mu3ServerImpl server;
    @NotNull
    protected final ConnectionAcceptor creator;
    @NotNull
    protected final Socket clientSocket;
    @Nullable
    protected final Certificate clientCertificate;
    @NotNull
    protected final Instant handshakeStartTime;
    protected final long connectionStartTime = System.currentTimeMillis();
    protected final InetSocketAddress remoteAddress;
    protected final InetSocketAddress localAddress;
    protected volatile long lastIO = connectionStartTime;
    protected final AtomicLong completedRequests = new AtomicLong(0);
    protected final AtomicLong invalidHttpRequests = new AtomicLong(0);
    protected final AtomicLong rejectedDueToOverload = new AtomicLong(0);
    protected final AtomicBoolean closed = new AtomicBoolean(false);
    protected final int requestTimeout;

    BaseHttpConnection(@NotNull Mu3ServerImpl server, @NotNull ConnectionAcceptor creator, @NotNull Socket clientSocket, @Nullable Certificate clientCertificate, @NotNull Instant handshakeStartTime) {
        this.server = server;
        this.creator = creator;
        this.clientSocket = clientSocket;
        this.clientCertificate = clientCertificate;
        this.handshakeStartTime = handshakeStartTime;
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

    protected void onRequestEnded(Mu3Request req, Mu3Response resp) {
        completedRequests.incrementAndGet();
        for (var listener : resp.completionListeners()) {
            listener.onComplete(resp);
        }
        server.onRequestEnded(req, resp);
    }


    @Override
    public long idleTimeMillis() {
        return System.currentTimeMillis() - lastIO;
    }
    @Override
    public boolean isHttps() {
        return creator.isHttps();
    }
    @Override
    public String httpsProtocol() {
        if (clientSocket instanceof SSLSocket) {
            return ((SSLSocket)clientSocket).getSession().getProtocol();
        } else {
            return null;
        }
    }
    @Override
    public String cipher() {
        if (clientSocket instanceof SSLSocket) {
            return ((SSLSocket)clientSocket).getSession().getCipherSuite();
        } else {
            return null;
        }
    }
    @NotNull
    @Override
    public Instant startTime() {
        return this.handshakeStartTime;
    }


    @Override
    public long handshakeDurationMillis() {
        return connectionStartTime - handshakeStartTime.toEpochMilli();
    }

    @NotNull
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

    @NotNull
    @Override
    public MuServer server() { return server; }

    @NotNull
    public Mu3ServerImpl serverImpl() { return server; }

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

    protected void onBytesRead(int read) {
        onIO();
        server.getStatsImpl().onBytesRead(read);
    }

    protected void onBytesRead(byte[] buffer, int off, int len) {
        onIO();
        server.getStatsImpl().onBytesRead(len);
    }

    private void onIO() {
        lastIO = System.currentTimeMillis();
    }

    public long lastIO() {
        return lastIO;
    }

    @Override
    public String toString() {
        return httpVersion().version() + " connection from " + remoteAddress + " to " + localAddress;
    }

}
