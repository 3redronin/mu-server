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
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

class ConnectionAcceptor {
    private static final Logger log = LoggerFactory.getLogger(ConnectionAcceptor.class);

    private final Mu3ServerImpl server;
    private final ServerSocket socketServer;
    private final InetSocketAddress address;
    private final URI uri;
    private volatile @Nullable HttpsConfig httpsConfig;
    private final @Nullable Http2Config http2Config;
    private final ExecutorService executorService;
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

    private final ConcurrentHashMap.KeySetView<BaseHttpConnection, Boolean> connections = ConcurrentHashMap.newKeySet();

    public Set<HttpConnection> activeConnections() {
        return Set.copyOf(connections);
    }

    private volatile State state = State.NOT_STARTED;

    private final boolean isHttps;

    private final Thread acceptorThread;
    private final @Nullable Thread timeoutThread;

    ConnectionAcceptor(Mu3ServerImpl server, ServerSocket socketServer, InetSocketAddress address, URI uri,
                       @Nullable HttpsConfig httpsConfig, @Nullable Http2Config http2Config,
                       ExecutorService executorService, List<ContentEncoder> contentEncoders) {
        this.server = server;
        this.socketServer = socketServer;
        this.address = address;
        this.uri = uri;
        this.httpsConfig = httpsConfig;
        this.http2Config = http2Config;
        this.executorService = executorService;
        this.contentEncoders = contentEncoders;
        this.isHttps = httpsConfig != null;

        this.acceptorThread = new Thread(this::acceptLoop, toString());
        this.timeoutThread = server.idleTimeoutMillis() == 0 ? null :
            new Thread(this::timeoutLoop, this + "-watcher");
    }


    private void acceptLoop() {
        boolean h2 = http2Config != null && http2Config.enabled();
        while (state == State.STARTED) {
            try {
                Socket clientSocket = socketServer.accept();
                Instant startTime = Instant.now();
                try {
                    executorService.submit(() -> {
                        handleClientSocket(clientSocket, startTime, h2, false);
                    });
                } catch (RejectedExecutionException e) {
                    int oldTimeout = clientSocket.getSoTimeout();
                    try {
                        clientSocket.setSoTimeout(2000);
                        handleClientSocket(clientSocket, startTime, false, true);
                    } catch (Exception e2) {
                        log.info("Exception while writing 503 when executor is full: {}", e.getMessage());
                    } finally {
                        clientSocket.setSoTimeout(oldTimeout);
                    }
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
        shutdownConnections();
    }

    private void shutdownConnections() {
        log.info("Closing server with " + connections.size() + " connected connections");
        Instant waitUntil = Instant.now().plusSeconds(20);
        for (BaseHttpConnection connection : connections) {
            try {
                connection.initiateGracefulShutdown();
            } catch (IOException ignored) {
            }
        }
        while (!connections.isEmpty() && waitUntil.isAfter(Instant.now())) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (BaseHttpConnection connection : connections) {
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
    }

    private void timeoutLoop() {
        while (state == State.STARTED) {
            try {
                long cutoff = System.currentTimeMillis() - server.idleTimeoutMillis();
                for (BaseHttpConnection con : connections) {
                    if (con.lastIO() < cutoff) {
                        log.info("Timing out {}", con);
                        con.abortWithTimeout();
                    }
                }
                Thread.sleep(200);
            } catch (Throwable t) {
                if (state == State.STARTED) {
                    log.error("Exception while doing timeouts", t);
                }
            }
        }
    }

    private void handleClientSocket(Socket clientSocket, Instant startTime, boolean http2Enabled, boolean rejectDueToOverload) {
        Socket socket = clientSocket;
        Certificate clientCert = null;

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

                var clientAuthTrustManager = hc.clientAuthTrustManager();
                secureSocket.setWantClientAuth(clientAuthTrustManager != null);

                secureSocket.addHandshakeCompletedListener(event ->
                    log.debug("Handshake complete {}", event));

                secureSocket.startHandshake();
                log.debug("Selected protocol is {}", secureSocket.getApplicationProtocol());

                if ("h2".equals(secureSocket.getApplicationProtocol())) {
                    httpVersion = HttpVersion.HTTP_2;
                }

                if (clientAuthTrustManager != null) {
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
        }

        if (rejectDueToOverload) {
            handleOverload(socket);
        } else {
            handleRequest(socket, clientCert, startTime, httpVersion);
        }
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

    private void handleRequest(Socket socket, @Nullable Certificate clientCert, Instant startTime, HttpVersion httpVersion) {
        BaseHttpConnection con = httpVersion == HttpVersion.HTTP_2
            ? new Http2Connection(server, this, socket, clientCert, startTime, http2Config.initialSettings(), executorService)
            : new Http1Connection(server, this, socket, clientCert, startTime);

        connections.add(con);
        server.getStatsImpl().onConnectionOpened(con);
        try {
            try (OutputStream clientOut = socket.getOutputStream();
                 InputStream clientIn = new HttpConnectionInputStream(con, socket.getInputStream())) {
                con.start(clientIn, clientOut);
            }
        } catch (Throwable t) {
            log.error("Unhandled exception for {}", con, t);
        } finally {
            server.getStatsImpl().onConnectionClosed(con);
            connections.remove(con);
        }
    }

    public void start() {
        if (state != State.STOPPED && state != State.NOT_STARTED) {
            throw new IllegalStateException("Cannot start with state " + state);
        }
        acceptorThread.setDaemon(false);
        state = State.STARTED;
        acceptorThread.start();
        if (timeoutThread != null) {
            timeoutThread.start();
        }
    }

    public void stop(long timeoutMillis) {
        log.info("Stopping server 1");
        state = State.STOPPING;
        long start = System.currentTimeMillis();
        if (timeoutThread != null) {
            timeoutThread.interrupt();
            try {
                timeoutThread.join(timeoutMillis);
            } catch (InterruptedException ignored) {

            }
        }
        try {
            socketServer.close();
        } catch (IOException e) {
            log.warn("Error closing server socket", e);
        }
        long durationSoFar = System.currentTimeMillis() - start;
        try {
            acceptorThread.join(Math.max(1, timeoutMillis - durationSoFar));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (acceptorThread.isAlive()) {
            log.warn("Could not kill " + this + " after " + timeoutMillis + " ms");
        }
        state = State.STOPPED;
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
        ExecutorService executor,
        List<ContentEncoder> contentEncoders) throws IOException {

        ServerSocket socketServer = new ServerSocket(bindPort, 50, address);
        configureSocketOptions(socketServer);

        String uriHost = address != null ? address.getHostName() : "localhost";
        URI uri = URI.create("http" + (httpsConfig == null ? "" : "s") + "://" + uriHost + ":" + socketServer.getLocalPort());

        return new ConnectionAcceptor(server, socketServer,
            (InetSocketAddress) socketServer.getLocalSocketAddress(),
            uri, httpsConfig, h2Config, executor, contentEncoders);
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