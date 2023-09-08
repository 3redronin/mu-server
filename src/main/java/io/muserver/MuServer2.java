package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.net.URI;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

class MuServer2 implements MuServer {
    private static final Logger log = LoggerFactory.getLogger(MuServer2.class);

    // todo delete this and just get it from the acceptor
    private final ConcurrentHashMap.KeySetView<MuHttp1Connection,Boolean> connections = ConcurrentHashMap.newKeySet();
    private final List<ConnectionAcceptor> acceptors = new LinkedList<>();
    final List<MuHandler> handlers;
    final MuStats2Impl stats = new MuStats2Impl();
    final UnhandledExceptionHandler unhandledExceptionHandler;
    final MuServerSettings settings;
    private final List<ResponseCompleteListener> responseCompleteListeners;

    MuServer2(List<MuHandler> handlers, UnhandledExceptionHandler unhandledExceptionHandler, MuServerSettings settings, List<ResponseCompleteListener> responseCompleteListeners) {
        this.handlers = handlers;
        this.unhandledExceptionHandler = unhandledExceptionHandler;
        this.settings = settings;
        this.responseCompleteListeners = responseCompleteListeners;
    }

    void addAcceptor(ConnectionAcceptor acceptor) {
        acceptors.add(acceptor);
    }

    static MuServer start(MuServerBuilder builder) throws IOException {

        boolean hasHttps = builder.httpsPort() >= 0;

        // connect server socket channel and register it in the selector
        var bindPort = hasHttps ? builder.httpsPort() : builder.httpPort();
        InetSocketAddress endpoint = builder.interfaceHost() == null ? new InetSocketAddress(bindPort) : new InetSocketAddress(builder.interfaceHost(), bindPort);

        var settings = new MuServerSettings(builder.gzipEnabled(), builder.minimumGzipSize(), builder.mimeTypesToGzip(), builder.maxRequestSize(), builder.maxHeadersSize(), builder.maxUrlSize(), builder.idleTimeoutMills(), builder.requestReadTimeoutMillis(), builder.responseWriteTimeoutMillis(), builder.requestBodyTooLargeAction());

        MuServer2 server = new MuServer2(builder.handlers(), builder.unhandledExceptionHandler(), settings, builder.responseCompleteListeners());
        if (!hasHttps) {
            server.addAcceptor(createAcceptor(server, null, endpoint));
        } else {
            // initialize the SSLContext, a configuration holder, reusable object
            HttpsConfigBuilder httpsConfigBuilder = builder.httpsConfigBuilder();
            if (httpsConfigBuilder == null) {
                httpsConfigBuilder = HttpsConfigBuilder.unsignedLocalhost();
            }
            HttpsConfig httpsConfig = httpsConfigBuilder.build2();
            server.addAcceptor(createAcceptor(server, httpsConfig, endpoint));
            httpsConfig.setHttpsUri(server.httpsUri());
        }

        return server;
    }

    private static ConnectionAcceptor createAcceptor(MuServer2 muServer, HttpsConfig httpsConfig, InetSocketAddress bindAddress) throws IOException {

        var serverSocketChannel = AsynchronousServerSocketChannel.open();
        var supportedOptions = serverSocketChannel.supportedOptions();
        Map<SocketOption<?>, ?> requestedOptions = Map.of(StandardSocketOptions.SO_REUSEADDR, true, StandardSocketOptions.SO_REUSEPORT, true);
        Map<SocketOption<?>, Object> appliedOptions = new HashMap<>();
        for (Map.Entry<SocketOption<?>, ?> requested : requestedOptions.entrySet()) {
            SocketOption<? super Object> key = (SocketOption<? super Object>) requested.getKey();
            if (supportedOptions.contains(key)) {
                Object value = requested.getValue();
                serverSocketChannel.setOption((SocketOption<? super Object>) key, value);
                appliedOptions.put(key, value);
            }
        }
        for (Map.Entry<SocketOption<?>, Object> entry : appliedOptions.entrySet()) {
            log.info("Applied socket option " + entry.getKey() + "=" + entry.getValue());
        }

        serverSocketChannel.bind(bindAddress);
        InetSocketAddress boundAddress = (InetSocketAddress) serverSocketChannel.getLocalAddress();
        ConnectionAcceptor acceptor = new ConnectionAcceptor(serverSocketChannel, boundAddress, httpsConfig);
        acceptor.readyToAccept(muServer);

        return acceptor;
    }

    @Override
    public void stop() {
        stop(5, TimeUnit.SECONDS);
    }

    @Override
    public void stop(long timeout, TimeUnit unit) {
        log.info("Stopping acceptors");

        for (ConnectionAcceptor acceptor : acceptors) {
            try {
                acceptor.stopAccepting();
            } catch (IOException e) {
                log.warn("Error stopping " + acceptor + ": " + e.getMessage());
            }
        }
        log.info("Acceptors stopped");

        var now = System.currentTimeMillis();
        long timeoutMillis = unit.toMillis(timeout);
        while (stats.activeConnections() > 0) {
            if (!((System.currentTimeMillis() - now) < timeoutMillis)) break;
            try {
                log.info("Waiting for " + stats.activeConnections() + " connections to finish");
                Thread.sleep(50);
            } catch (InterruptedException e) {
                break;
            }
        }
        for (ConnectionAcceptor acceptor : acceptors) {
            acceptor.killConnections();
        }

        log.info("Stop completed");
    }

    @Override
    public URI uri() {
        var https = httpsUri();
        if (https != null) {
            return https;
        }
        return httpUri();
    }

    @Override
    public URI httpUri() {
        for (ConnectionAcceptor acceptor : acceptors) {
            if (acceptor.acceptsHttp()) {
                return acceptor.uri;
            }
        }
        return null;
    }

    @Override
    public URI httpsUri() {
        for (ConnectionAcceptor acceptor : acceptors) {
            if (acceptor.acceptsHttps()) {
                return acceptor.uri;
            }
        }
        return null;
    }

    @Override
    public MuStats stats() {
        return stats;
    }

    @Override
    public Set<HttpConnection> activeConnections() {
        return connections.stream().map(HttpConnection.class::cast).collect(Collectors.toSet());
    }

    @Override
    public InetSocketAddress address() {
        return connections.stream().map(MuHttp1Connection::remoteAddress).findFirst().get();
    }

    @Override
    public long minimumGzipSize() {
        return settings.minGzipSize();
    }

    @Override
    public int maxRequestHeadersSize() {
        return settings.maxHeadersSize();
    }

    @Override
    public long requestIdleTimeoutMillis() {
        return settings.requestReadTimeoutMillis();
    }

    @Override
    public long maxRequestSize() {
        return settings.maxRequestSize();
    }

    @Override
    public int maxUrlSize() {
        return settings.maxUrlSize();
    }

    @Override
    public boolean gzipEnabled() {
        return settings.gzipEnabled();
    }

    @Override
    public Set<String> mimeTypesToGzip() {
        return settings.mimeTypesToGzip();
    }

    @Override
    public void changeHttpsConfig(HttpsConfigBuilder newHttpsConfig) {
        changeHttpsConfig(newHttpsConfig.build2());
    }
    @Override
    public void changeHttpsConfig(HttpsConfig newHttpsConfig) {
        for (ConnectionAcceptor acceptor : acceptors) {
            if (acceptor.acceptsHttps()) {
                acceptor.changeHttpsConfig(newHttpsConfig);
            }
        }
    }

    @Override
    public SSLInfo sslInfo() {
        return httpsConfig();
    }

    @Override
    public HttpsConfig httpsConfig() {
        return acceptors.stream().map(ConnectionAcceptor::httpsConfig).filter(Objects::nonNull).findFirst().orElse(null);
    }

    @Override
    public List<RateLimiter> rateLimiters() {
        return null;
    }

    public void onConnectionAccepted(MuHttp1Connection connection) {
        log.info("New connection from " + connection.remoteAddress());
        stats.onConnectionOpened();
        connections.add(connection);
    }


    void onConnectionFailed(MuHttp1Connection connection, Throwable exc) {
        log.info("Connection failed: " + exc.getMessage());
        onConnectionEnded(connection);
    }

    void onConnectionEnded(MuHttp1Connection connection) {
        if (connections.remove(connection)) {
            log.info("Connection ended: " + connection);
            stats.onConnectionClosed();
        }
    }

    public void onExchangeStarted(MuExchange exchange) {
        stats.onRequestStarted(exchange.request);
    }

    public void onExchangeComplete(MuExchange exchange) {
        stats.onRequestEnded(exchange.request);
        for (ResponseCompleteListener listener : responseCompleteListeners) {
            listener.onComplete(exchange);
        }
    }

    public void onInvalidRequest(InvalidRequestException e) {
        stats.onInvalidRequest();
    }
}


record MuServerSettings(boolean gzipEnabled, long minGzipSize, Set<String> mimeTypesToGzip, long maxRequestSize, int maxHeadersSize, int maxUrlSize,
                        long idleTimeoutMills, long requestReadTimeoutMillis, long responseWriteTimeoutMillis, RequestBodyErrorAction requestBodyTooLargeAction) {
}