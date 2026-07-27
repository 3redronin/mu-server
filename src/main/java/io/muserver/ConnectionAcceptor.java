package io.muserver;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

class ConnectionAcceptor {
    private static final Logger log = LoggerFactory.getLogger(ConnectionAcceptor.class);
    private static final int ACCEPT_BACKLOG = 50;

    private final Mu3ServerImpl server;
    private final ServerSocket socketServer;
    private final InetSocketAddress address;
    private final URI uri;
    private volatile @Nullable HttpsConfig httpsConfig;
    private final @Nullable Http2Config http2Config;
    private final ExecutorService handlerExecutor;
    private final ExecutorService connectionExecutor;
    private final ExecutorService http2WriterExecutor;
    private final List<ContentEncoder> contentEncoders;

    public boolean isHttps() {
        return isHttps;
    }
    public URI uri() {
        return uri;
    }

    public InetSocketAddress address() {
        return address;
    }

    public List<ContentEncoder> contentEncoders() {
        return contentEncoders;
    }

    public @Nullable HttpsConfig httpsConfig() {
        return httpsConfig;
    }

    private enum State { NOT_STARTED, STARTED, STOPPING, STOPPED }

    // Owns listener state, accepted-to-live connection promotion, and shutdown
    // drain signalling. Socket close and connection callbacks happen outside it.
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final Condition connectionRetired = lifecycleLock.newCondition();
    private final Set<Socket> acceptedSockets = new HashSet<>();
    private final Set<BaseHttpConnection> connections = new HashSet<>();

    public Set<HttpConnection> activeConnections() {
        lifecycleLock.lock();
        try {
            return Set.copyOf(connections);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private volatile State state = State.NOT_STARTED;
    private volatile long gracefulShutdownTimeoutMillis = 20_000;
    private volatile long gracefulShutdownDeadlineNanos = Long.MAX_VALUE;
    private volatile boolean lastStopWasGraceful = true;

    private final boolean isHttps;

    private final Thread acceptorThread;
    private volatile @Nullable ScheduledFuture<?> timeoutTask;

    ConnectionAcceptor(Mu3ServerImpl server, ServerSocket socketServer, InetSocketAddress address, URI uri,
                       @Nullable HttpsConfig httpsConfig, @Nullable Http2Config http2Config,
                       ExecutorService handlerExecutor, ExecutorService connectionExecutor,
                       ExecutorService http2WriterExecutor,
                       List<ContentEncoder> contentEncoders) {
        this.server = server;
        this.socketServer = socketServer;
        this.address = address;
        this.uri = uri;
        this.httpsConfig = httpsConfig;
        this.http2Config = http2Config;
        this.handlerExecutor = handlerExecutor;
        this.connectionExecutor = connectionExecutor;
        this.http2WriterExecutor = http2WriterExecutor;
        this.contentEncoders = contentEncoders;
        this.isHttps = httpsConfig != null;

        this.acceptorThread = new Thread(this::acceptLoop, toString());
    }


    private void acceptLoop() {
        boolean h2 = http2Config != null && http2Config.enabled();
        while (state == State.STARTED) {
            try {
                Socket clientSocket = socketServer.accept();
                clientSocket.setTcpNoDelay(true);
                if (!registerAcceptedSocket(clientSocket)) {
                    closeQuietly(clientSocket);
                    continue;
                }
                Instant startTime = Instant.now();
                try {
                    connectionExecutor.submit(
                        () -> runAcceptedSocket(clientSocket, startTime, h2, false)
                    );
                } catch (RejectedExecutionException e) {
                    try {
                        clientSocket.setSoTimeout(2000);
                        runAcceptedSocket(clientSocket, startTime, false, true);
                    } catch (Exception e2) {
                        log.info("Exception while writing 503 when executor is full: {}", e2.getMessage());
                    }
                } catch (RuntimeException | Error submissionFailure) {
                    retireAcceptedSocket(clientSocket);
                    closeQuietly(clientSocket);
                    throw submissionFailure;
                }
            } catch (Throwable e) {
                if (Thread.interrupted() || e instanceof SocketException) {
                    log.info("Accept listening stopped");
                } else {
                    log.info("Exception when state={}", state, e);
                    if (state == State.STARTED) {
                        log.warn("Error while accepting from {}", this, e);
                    }
                }
            }
        }
        lastStopWasGraceful = shutdownConnections();
        lifecycleLock.lock();
        try {
            state = State.STOPPED;
            connectionRetired.signalAll();
        } finally {
            lifecycleLock.unlock();
        }
    }

    private boolean registerAcceptedSocket(Socket socket) {
        lifecycleLock.lock();
        try {
            if (state != State.STARTED) {
                return false;
            }
            acceptedSockets.add(socket);
            return true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void runAcceptedSocket(
        Socket socket,
        Instant startTime,
        boolean http2Enabled,
        boolean rejectDueToOverload
    ) {
        try {
            handleClientSocket(
                socket,
                startTime,
                http2Enabled,
                rejectDueToOverload
            );
        } finally {
            retireAcceptedSocket(socket);
            closeQuietly(socket);
        }
    }

    private void retireAcceptedSocket(Socket socket) {
        lifecycleLock.lock();
        try {
            acceptedSockets.remove(socket);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private boolean shutdownConnections() {
        closePendingAcceptedSockets();
        List<BaseHttpConnection> active = connectionSnapshot();
        log.info("Closing server with " + active.size() + " connected connections");
        if (gracefulShutdownDeadlineNanos == Long.MAX_VALUE) {
            gracefulShutdownDeadlineNanos =
                MonotonicTime.deadlineAfterMillis(
                    gracefulShutdownTimeoutMillis
                );
        }
        for (BaseHttpConnection connection : active) {
            try {
                connection.initiateGracefulShutdown();
            } catch (IOException ignored) {
            }
        }
        boolean drainedCleanly = awaitConnectionsRetired();
        for (BaseHttpConnection connection : connectionSnapshot()) {
            log.info("Force closure of active connection {} with requests {}", connection, connection.activeRequests());
            try {
                connection.abort();
            } catch (IOException e) {
                log.warn("Error aborting connection {}", connection, e);
            }
        }
        try {
            socketServer.close();
        } catch (IOException e) {
            log.warn("Error closing socket server", e);
        }
        log.info("Closed");
        return drainedCleanly;
    }

    private void closePendingAcceptedSockets() {
        List<Socket> pending;
        lifecycleLock.lock();
        try {
            pending = new ArrayList<>(acceptedSockets);
            acceptedSockets.clear();
        } finally {
            lifecycleLock.unlock();
        }
        for (Socket socket : pending) {
            closeQuietly(socket);
        }
    }

    private List<BaseHttpConnection> connectionSnapshot() {
        lifecycleLock.lock();
        try {
            return new ArrayList<>(connections);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private boolean awaitConnectionsRetired() {
        lifecycleLock.lock();
        try {
            while (!connections.isEmpty()) {
                long remaining = MonotonicTime.nanosUntil(
                    gracefulShutdownDeadlineNanos
                );
                if (remaining <= 0L) {
                    return false;
                }
                try {
                    connectionRetired.awaitNanos(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void checkIdleTimeouts() {
        if (state != State.STARTED) {
            return;
        }
        try {
            long nowNanos = System.nanoTime();
            long idleTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(
                server.idleTimeoutMillis()
            );
            for (BaseHttpConnection con : connectionSnapshot()) {
                if (con.hasBeenIdleFor(nowNanos, idleTimeoutNanos)) {
                    log.info("Timing out {}", con);
                    con.abortWithTimeout();
                }
            }
        } catch (Throwable t) {
            if (state == State.STARTED) {
                log.error("Exception while doing timeouts", t);
            }
        }
    }

    private void handleClientSocket(Socket clientSocket, Instant startTime, boolean http2Enabled, boolean rejectDueToOverload) {
        Socket socket = clientSocket;
        Certificate clientCert = null;
        PushbackInputStream inputStream = null;

        HttpsConfig hc = httpsConfig;
        HttpVersion httpVersion = HttpVersion.HTTP_1_1;

        if (hc != null) {
            try {
                SSLSocket secureSocket = (SSLSocket) hc.sslContext().getSocketFactory()
                    .createSocket(socket, null, socket.getPort(), true);
                secureSocket.setUseClientMode(false);
                secureSocket.setEnabledProtocols(hc.protocolsArray());
                secureSocket.setEnabledCipherSuites(hc.cipherSuitesArray());

                if (http2Enabled) {
                    secureSocket.setSSLParameters(createSSLParameters(secureSocket));
                }

                var clientCertificateAuthentication = hc.clientCertificateAuthentication();
                switch (clientCertificateAuthentication) {
                    case OPTIONAL:
                        secureSocket.setWantClientAuth(true);
                        break;
                    case MANDATORY:
                        secureSocket.setNeedClientAuth(true);
                        break;
                    default:
                        secureSocket.setWantClientAuth(false);
                }

                secureSocket.addHandshakeCompletedListener(event ->
                    log.debug("Handshake complete {}", event));

                secureSocket.startHandshake();
                log.debug("Selected protocol is {}", secureSocket.getApplicationProtocol());

                if ("h2".equals(secureSocket.getApplicationProtocol())) {
                    httpVersion = HttpVersion.HTTP_2;
                }

                if (clientCertificateAuthentication != ClientCertificateAuthentication.NONE) {
                    try {
                        Certificate[] certs = secureSocket.getSession().getPeerCertificates();
                        if (certs != null && certs.length > 0) {
                            clientCert = certs[0];
                        }
                    } catch (SSLPeerUnverifiedException ignored) {
                        // let the handler handle a lack of cert if they want
                    }
                }

                socket = secureSocket;
            } catch (Exception e) {
                log.warn("Failed TLS handshaking", e);
                server.getStatsImpl().onFailedToConnect();
                return;
            }
        } else if (http2Enabled) {
            try {
                inputStream = new PushbackInputStream(socket.getInputStream(), Http2Handshaker.clientConnectionPrefaceLength());
                httpVersion = sniffClearTextHttpVersion(socket, inputStream);
            } catch (IOException e) {
                log.info("Failed while checking for cleartext HTTP/2 prior knowledge: {}", e.getMessage());
                return;
            }
        }

        if (rejectDueToOverload) {
            handleOverload(socket);
        } else {
            handleRequest(
                clientSocket,
                socket,
                clientCert,
                startTime,
                httpVersion,
                inputStream
            );
        }
    }

    private HttpVersion sniffClearTextHttpVersion(Socket socket, PushbackInputStream inputStream) throws IOException {
        byte[] prefix = new byte[Http2Handshaker.clientConnectionPrefaceLength()];
        int read = 0;
        int originalTimeout = socket.getSoTimeout();
        socket.setSoTimeout((int)Math.min(Integer.MAX_VALUE, server.requestIdleTimeoutMillis()));
        try {
            while (read < prefix.length) {
                int next = inputStream.read();
                if (next == -1) {
                    break;
                }
                prefix[read++] = (byte) next;
                if (!Http2Handshaker.isClientPrefacePrefix(prefix, read)) {
                    break;
                }
            }
        } finally {
            socket.setSoTimeout(originalTimeout);
            if (read > 0) {
                inputStream.unread(prefix, 0, read);
            }
        }
        return read == prefix.length ? HttpVersion.HTTP_2 : HttpVersion.HTTP_1_1;
    }

    private void handleOverload(Socket socket) {
        // At this point, the server is overloaded. We want to send 503 responses to clients
        // so they know the server is not available, but on the other hand we don't want to
        // spend resources reading or writing to slow clients, so we have smaller timeouts
        // and don't read large requests.
        server.getStatsImpl().onRejectedDueToOverload();
        try {
            socket.setSoTimeout(2000);
            try (InputStream inputStream = socket.getInputStream();
                 OutputStream os = socket.getOutputStream()) {
                os.write(serverUnavailableResponse);
                os.flush();
                byte[] buf = new byte[1024];
                int reads = 0;
                // consume the request body so it's a valid response, but only if it's not too big
                while (inputStream.read(buf) != -1 && reads < 10) {
                    reads++;
                }
            }
        } catch (IOException e) {
            log.warn("Error handling overload", e);
        }
    }

    @SuppressWarnings("ReferenceEquality") // Sharing is defined by the exact configured executor instance.
    private void handleRequest(
        Socket acceptedSocket,
        Socket socket,
        @Nullable Certificate clientCert,
        Instant startTime,
        HttpVersion httpVersion,
        @Nullable InputStream providedInputStream
    ) {
        BaseHttpConnection con;
        if (httpVersion == HttpVersion.HTTP_2) {
            if (http2Config == null) {
                throw new IllegalStateException("HTTP/2 was selected but no HTTP/2 config is available");
            }
            con = new Http2Connection(
                server,
                this,
                socket,
                clientCert,
                startTime,
                http2Config.initialSettings(),
                http2Config.settingsAckTimeoutMillis(),
                handlerExecutor,
                http2WriterExecutor
            );
        } else {
            con = new Http1Connection(
                server,
                this,
                socket,
                clientCert,
                startTime,
                handlerExecutor,
                handlerExecutor == connectionExecutor
            );
        }

        if (!promoteAcceptedSocket(acceptedSocket, con)) {
            return;
        }
        server.getStatsImpl().onConnectionOpened(con);
        try {
            InputStream requestIn = providedInputStream == null ? socket.getInputStream() : providedInputStream;
            try (OutputStream clientOut = new HttpConnectionOutputStream(
                     con,
                     socket.getOutputStream()
                 );
                 InputStream clientIn = new HttpConnectionInputStream(con, requestIn)) {
                con.start(clientIn, clientOut);
            }
        } catch (Throwable t) {
            log.error("Unhandled exception for {}", con, t);
        } finally {
            server.getStatsImpl().onConnectionClosed(con);
            retireConnection(con);
        }
    }

    private boolean promoteAcceptedSocket(
        Socket acceptedSocket,
        BaseHttpConnection connection
    ) {
        lifecycleLock.lock();
        try {
            acceptedSockets.remove(acceptedSocket);
            if (state != State.STARTED) {
                return false;
            }
            connections.add(connection);
            return true;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void retireConnection(BaseHttpConnection connection) {
        lifecycleLock.lock();
        try {
            if (connections.remove(connection)) {
                connectionRetired.signalAll();
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    public void start() {
        lifecycleLock.lock();
        try {
            if (state != State.STOPPED && state != State.NOT_STARTED) {
                throw new IllegalStateException("Cannot start with state " + state);
            }
            state = State.STARTED;
        } finally {
            lifecycleLock.unlock();
        }
        acceptorThread.setDaemon(false);
        try {
            if (server.idleTimeoutMillis() > 0) {
                timeoutTask = server.scheduleConnectionTaskAtFixedRate(
                    this::checkIdleTimeouts,
                    0,
                    200,
                    TimeUnit.MILLISECONDS
                );
            }
            acceptorThread.start();
        } catch (RuntimeException | Error e) {
            ScheduledFuture<?> currentTimeoutTask = timeoutTask;
            if (currentTimeoutTask != null) {
                currentTimeoutTask.cancel(false);
                timeoutTask = null;
            }
            lifecycleLock.lock();
            try {
                state = State.NOT_STARTED;
            } finally {
                lifecycleLock.unlock();
            }
            throw e;
        }
    }

    public boolean stop(long timeoutMillis) {
        long stopTimeoutMillis = Math.max(0L, timeoutMillis);
        long callerDeadlineNanos = MonotonicTime.deadlineAfterMillis(
            stopTimeoutMillis
        );
        lifecycleLock.lock();
        try {
            if (state == State.STOPPED) {
                return lastStopWasGraceful;
            }
            log.info("Stopping server 1");
            if (state != State.STOPPING) {
                gracefulShutdownTimeoutMillis = stopTimeoutMillis;
                gracefulShutdownDeadlineNanos = callerDeadlineNanos;
                state = State.STOPPING;
            } else if (MonotonicTime.isAfter(
                callerDeadlineNanos,
                gracefulShutdownDeadlineNanos
            )) {
                // A callback-local stop can have a shorter deadline than a concurrent
                // external stop. Preserve the longer opportunity to drain.
                gracefulShutdownDeadlineNanos = callerDeadlineNanos;
                connectionRetired.signalAll();
            }
        } finally {
            lifecycleLock.unlock();
        }
        closePendingAcceptedSockets();
        ScheduledFuture<?> currentTimeoutTask = timeoutTask;
        if (currentTimeoutTask != null) {
            currentTimeoutTask.cancel(false);
            timeoutTask = null;
        }
        try {
            socketServer.close();
        } catch (IOException e) {
            log.warn("Error closing server socket", e);
        }
        joinAcceptorUntil(callerDeadlineNanos);
        if (acceptorThread.isAlive()) {
            log.warn("Could not kill " + this + " after " + timeoutMillis + " ms");
            lastStopWasGraceful = false;
            return false;
        }
        lifecycleLock.lock();
        try {
            state = State.STOPPED;
            return lastStopWasGraceful;
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void joinAcceptorUntil(long deadlineNanos) {
        while (acceptorThread.isAlive()) {
            long remaining = MonotonicTime.nanosUntil(deadlineNanos);
            if (remaining <= 0L) {
                return;
            }
            long millis = TimeUnit.NANOSECONDS.toMillis(remaining);
            int nanos = (int) (
                remaining - TimeUnit.MILLISECONDS.toNanos(millis)
            );
            try {
                acceptorThread.join(millis, nanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public String toString() {
        return "mu-acceptor-" + address.getPort();
    }

    public void changeHttpsConfig(HttpsConfig newHttpsConfig) {
        newHttpsConfig.setHttpsUri(uri);
        this.httpsConfig = newHttpsConfig;
    }

    public static ConnectionAcceptor create(
        Mu3ServerImpl server,
        @Nullable InetAddress address,
        int bindPort,
        @Nullable HttpsConfig httpsConfig,
        @Nullable Http2Config h2Config,
        ExecutorService handlerExecutor,
        ExecutorService connectionExecutor,
        ExecutorService http2WriterExecutor,
        List<ContentEncoder> contentEncoders) throws IOException {

        ServerSocket socketServer = new ServerSocket(bindPort, ACCEPT_BACKLOG, address);
        try {
            configureSocketOptions(socketServer);

            String uriHost = address != null ? address.getHostName() : "localhost";
            URI uri = URI.create("http" + (httpsConfig == null ? "" : "s") + "://" + uriHost + ":" + socketServer.getLocalPort());

            return new ConnectionAcceptor(server, socketServer,
                (InetSocketAddress) socketServer.getLocalSocketAddress(),
                uri, httpsConfig, h2Config, handlerExecutor, connectionExecutor, http2WriterExecutor, contentEncoders);
        } catch (IOException | RuntimeException | Error creationFailure) {
            try {
                socketServer.close();
            } catch (IOException cleanupFailure) {
                creationFailure.addSuppressed(cleanupFailure);
            }
            throw creationFailure;
        }
    }

    private static void configureSocketOptions(ServerSocket socketServer) throws IOException {
        Set<SocketOption<?>> supportedOptions = socketServer.supportedOptions();
        Map<SocketOption<?>, Object> requestedOptions = Map.of(
            StandardSocketOptions.SO_REUSEADDR, true,
            StandardSocketOptions.SO_REUSEPORT, true
        );

        Map<SocketOption<?>, Object> appliedOptions = new HashMap<>();
        for (Map.Entry<SocketOption<?>, Object> entry : requestedOptions.entrySet()) {
            @SuppressWarnings("unchecked")
            SocketOption<Object> key = (SocketOption<Object>) entry.getKey();
            if (supportedOptions.contains(key)) {
                Object value = entry.getValue();
                socketServer.setOption(key, value);
                appliedOptions.put(key, value);
            }
        }

        for (Map.Entry<SocketOption<?>, Object> entry : appliedOptions.entrySet()) {
            log.debug("Applied socket option {}={}", entry.getKey(), entry.getValue());
        }
    }

    private static SSLParameters createSSLParameters(SSLSocket secureSocket) {
        SSLParameters sslParams = secureSocket.getSSLParameters();
        sslParams.setApplicationProtocols(new String[]{"h2", "http/1.1"});
        return sslParams;
    }

    static final byte[] serverUnavailableResponse = (
        "HTTP/1.1 503 Service Unavailable\r\n" +
            "connection: close\r\n" +
            "content-type: text/plain;charset=utf-8\r\n" +
            "content-length: 23\r\n" +
            "\r\n" +
            "503 Service Unavailable"
    ).getBytes(StandardCharsets.US_ASCII);
}
