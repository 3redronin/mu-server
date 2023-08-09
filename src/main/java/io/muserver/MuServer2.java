package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.*;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

class MuServer2 implements MuServer {
    private static final Logger log = LoggerFactory.getLogger(MuServer2.class);
    private final ConcurrentHashMap.KeySetView<MuHttp1Connection,Boolean> connections = ConcurrentHashMap.newKeySet();
    private final List<ConnectionAcceptor> acceptors = new LinkedList<>();
    final List<MuHandler> handlers;
    final MuStats2Impl stats = new MuStats2Impl();

    MuServer2(List<MuHandler> handlers) {
        this.handlers = handlers;
    }

    void addAcceptor(ConnectionAcceptor acceptor) {
        acceptors.add(acceptor);
    }

    static MuServer start(MuServerBuilder builder) throws Exception {

        boolean hasHttps = builder.httpsPort() >= 0;

        // connect server socket channel and register it in the selector
        var bindPort = hasHttps ? builder.httpsPort() : builder.httpPort();
        InetSocketAddress endpoint = builder.interfaceHost() == null ? new InetSocketAddress(bindPort) : new InetSocketAddress(builder.interfaceHost(), bindPort);


        MuServer2 server = new MuServer2(builder.handlers());
        if (!hasHttps) {
            server.addAcceptor(createAcceptor(server, null, endpoint));
        } else {
            // initialize the SSLContext, a configuration holder, reusable object
            SSLContext sslContext = ContextFactory.authenticatedContext("TLSv1.3");
            server.addAcceptor(createAcceptor(server, sslContext, endpoint));
        }

        return server;
    }

    private static ConnectionAcceptor createAcceptor(MuServer2 muServer, SSLContext sslContext, InetSocketAddress bindAddress) throws IOException {

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
        ConnectionAcceptor acceptor = new ConnectionAcceptor(serverSocketChannel, boundAddress, sslContext);
        acceptor.readyToAccept(muServer);

        return acceptor;
    }

    @Override
    public void stop() {
        log.info("Stopping acceptors");

        for (ConnectionAcceptor acceptor : acceptors) {
            try {
                acceptor.stop();
            } catch (IOException e) {
                log.warn("Error stopping " + acceptor + ": " + e.getMessage());
            }
        }

        log.info("Acceptors stopped");


        for (MuHttp1Connection connection : connections) {
            try {
                log.info("Closing " + connection);
                connection.shutdown();
            } catch (IOException e) {
                log.warn("Error shutting down " + connection, e);
            }
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
        return 0;
    }

    @Override
    public int maxRequestHeadersSize() {
        return 0;
    }

    @Override
    public long requestIdleTimeoutMillis() {
        return 0;
    }

    @Override
    public long maxRequestSize() {
        return 0;
    }

    @Override
    public int maxUrlSize() {
        return 0;
    }

    @Override
    public boolean gzipEnabled() {
        return false;
    }

    @Override
    public Set<String> mimeTypesToGzip() {
        return null;
    }

    @Override
    public void changeHttpsConfig(HttpsConfigBuilder newHttpsConfig) {

    }

    @Override
    public SSLInfo sslInfo() {
        return null;
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

    public void onConnectionAcceptFailure(AsynchronousServerSocketChannel channel, Throwable exc) {
        stats.onFailedToConnect();
        if (channel.isOpen()) {
            SocketAddress localAddress;
            try {
                localAddress = channel.getLocalAddress();
            } catch (IOException e) {
                localAddress = null;
            }
            log.error("Error accepting on " + localAddress, exc);
        } else {
            log.info("Error on closed channel: " + exc.getMessage());
        }
    }

    public void onConnectionFailed(MuHttp1Connection connection, Throwable exc) {
        log.info("Connection failed: " + exc.getMessage());
        connections.remove(connection);
    }
}


class ConnectionAcceptor implements CompletionHandler<AsynchronousSocketChannel, MuServer2> {
    private final AsynchronousServerSocketChannel channel;

    final InetSocketAddress address;
    final SSLContext sslContext;
    final URI uri;
    private volatile boolean stopped = false;

    ConnectionAcceptor(AsynchronousServerSocketChannel channel, InetSocketAddress address, SSLContext sslContext) {
        this.channel = channel;
        this.address = address;
        this.sslContext = sslContext;
        this.uri = URI.create("http" + (sslContext == null ? "" : "s") + "://localhost:" + address.getPort());
    }

    boolean acceptsHttp() {
        return sslContext == null;
    }

    boolean acceptsHttps() {
        return sslContext != null;
    }

    public void readyToAccept(MuServer2 muServer) {
        if (!stopped) {
            channel.accept(muServer, this);
        }
    }

    /**
     * A new connection has been accepted
     */
    @Override
    public void completed(AsynchronousSocketChannel channel, MuServer2 muServer) {
        readyToAccept(muServer);
        InetSocketAddress remoteAddress;
        try {
            remoteAddress = (InetSocketAddress) channel.getRemoteAddress();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        MuHttp1Connection connection = new MuHttp1Connection(muServer, channel, remoteAddress);
        muServer.onConnectionAccepted(connection);
        connection.readyToRead();
    }

    @Override
    public void failed(Throwable exc, MuServer2 server) {
        readyToAccept(server);
        server.onConnectionAcceptFailure(channel, exc);
    }

    public void stop() throws IOException {
        stopped = true;
        channel.close();
    }
}
