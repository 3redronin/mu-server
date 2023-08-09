package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.ws.rs.NotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

class MuServer2 implements MuServer {
    private static final Logger log = LoggerFactory.getLogger(MuServer2.class);
    private final ConcurrentHashMap.KeySetView<MuHttpConnection,Boolean> connections = ConcurrentHashMap.newKeySet();
    private final List<ConnectionAcceptor> acceptors = new LinkedList<>();
    final List<MuHandler> handlers;

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


        for (MuHttpConnection connection : connections) {
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
        return null;
    }

    @Override
    public Set<HttpConnection> activeConnections() {
        return connections.stream().map(HttpConnection.class::cast).collect(Collectors.toSet());
    }

    @Override
    public InetSocketAddress address() {
        return null;
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

    public void onConnectionAccepted(MuHttpConnection connection) {
        log.info("New connection from " + connection.remoteAddress());
        connections.add(connection);
    }

    public void onConnectionAcceptFailure(AsynchronousServerSocketChannel channel, Throwable exc) {
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

    public void onConnectionFailed(MuHttpConnection connection, Throwable exc) {
        log.info("Connection failed: " + exc.getMessage());
        connections.remove(connection);
    }
}


class ConnectionAcceptor implements CompletionHandler<AsynchronousSocketChannel, MuServer2> {
    private static final Logger log = LoggerFactory.getLogger(ConnectionAcceptor.class);
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

    @Override
    public void completed(AsynchronousSocketChannel channel, MuServer2 muServer) {
        readyToAccept(muServer);
        InetSocketAddress remoteAddress;
        try {
            remoteAddress = (InetSocketAddress) channel.getRemoteAddress();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var requestParser = new RequestParser(new RequestParser.Options(8192, 8192), new RequestParser.RequestListener() {
            @Override
            public void onHeaders(Method method, URI uri, HttpVersion httpProtocolVersion, MuHeaders headers, GrowableByteBufferInputStream body) {
                var data = new MuExchangeData(null, httpProtocolVersion, headers);
                var req = new MuRequestImpl(data, method, uri, uri, headers);
                var resp = new MuResponseImpl(data, channel);
                var exchange = new MuExchange(data, req, resp);

                try {
                    boolean handled = false;
                    for (MuHandler muHandler : muServer.handlers) {
                        handled = muHandler.handle(req, resp);
                        if (handled) {
                            break;
                        }
                        if (req.isAsync()) {
                            throw new IllegalStateException(muHandler.getClass() + " returned false however this is not allowed after starting to handle a request asynchronously.");
                        }
                    }
                    if (!handled) {
                        throw new NotFoundException();
                    }
                    if (resp.responseState() == ResponseState.STREAMING) {
                        resp.endStreaming();
                    }
                } catch (Exception e) {
                    log.error("Unhandled exception", e);
                    throw new RuntimeException(e);
                }
            }

            @Override
            public void onRequestComplete(MuHeaders trailers) {
                log.info("Request complete. Trailers=" + trailers);
            }
        });

        MuHttpConnection connection = new MuHttpConnection(muServer, channel, remoteAddress);
        ByteBuffer res = ByteBuffer.allocate(10000);
        channel.read(res, channel, new CompletionHandler<>() {
            @Override
            public void completed(Integer result, AsynchronousSocketChannel channel) {
                if (result != -1) {
                    res.flip();
                    try {
                        requestParser.offer(res);
                    } catch (InvalidRequestException e) {
                        log.error("Invalid request", e);
                        throw new RuntimeException(e);
                    }
                    res.compact();
                    // repeat
                    channel.read(res, null, this);
                } else {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        log.error("What to do here", e);
                        throw new RuntimeException(e);
                    }
                }
            }

            @Override
            public void failed(Throwable exc, AsynchronousSocketChannel channel) {
                muServer.onConnectionFailed(connection, exc);
                var logIt = !(exc instanceof ClosedChannelException);
                if (logIt) { // TODO also log if requests are in progress
                    log.error("Read failed", exc);
                }
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException e) {
                        log.error("Error closing", e);
                        throw new RuntimeException(e);
                    }
                }
            }
        });
        muServer.onConnectionAccepted(new MuHttpConnection(muServer, channel, remoteAddress));
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
