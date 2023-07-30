package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tlschannel.ServerTlsChannel;
import tlschannel.TlsChannel;
import tlschannel.async.AsynchronousTlsChannel;
import tlschannel.async.AsynchronousTlsChannelGroup;

import javax.net.ssl.SSLContext;
import javax.ws.rs.NotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

class MuServer2 implements MuServer {
    private static final Logger log = LoggerFactory.getLogger(MuServer2.class);
    private final ServerSocketChannel socketChannel;
    private final AsynchronousTlsChannelGroup channelGroup;
    private final ConcurrentHashMap.KeySetView<MuHttpConnection,Boolean> connections;

    public MuServer2(ServerSocketChannel socketChannel, AsynchronousTlsChannelGroup channelGroup, ConcurrentHashMap.KeySetView<MuHttpConnection, Boolean> connections) {
        this.socketChannel = socketChannel;
        this.channelGroup = channelGroup;
        this.connections = connections;
    }

    static MuServer start(MuServerBuilder builder) throws Exception {
        // initialize the SSLContext, a configuration holder, reusable object
        SSLContext sslContext = ContextFactory.authenticatedContext("TLSv1.3");

        AsynchronousTlsChannelGroup channelGroup = new AsynchronousTlsChannelGroup();

        // connect server socket channel and register it in the selector
        ServerSocketChannel serverSocket = ServerSocketChannel.open();

        // accept raw connections normally
        log.info("Waiting for connection...");

        ConcurrentHashMap.KeySetView<MuHttpConnection,Boolean> connections = ConcurrentHashMap.newKeySet();

        Thread thread = createAcceptor(builder, sslContext, channelGroup, serverSocket, connections, builder.httpsPort());

        thread.start();

        return new MuServer2(serverSocket, channelGroup, connections);
    }

    private static MuHttpConnection createMuConnection(AsynchronousByteChannel channel, ConcurrentHashMap.KeySetView<MuHttpConnection,Boolean> connections, List<MuHandler> muHandlers) {
        var requestParser = new RequestParser(new RequestParser.Options(8192, 8192), new RequestParser.RequestListener() {
            @Override
            public void onHeaders(Method method, URI uri, HttpVersion httpProtocolVersion, MuHeaders headers, GrowableByteBufferInputStream body) {
                var data = new MuExchangeData(null, httpProtocolVersion, headers);
                var req = new MuRequestImpl(data, method, uri, uri, headers);
                var resp = new MuResponseImpl(data, channel);
                var exchange = new MuExchange(data, req, resp);

                try {
                    boolean handled = false;
                    for (MuHandler muHandler : muHandlers) {
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

        MuHttpConnection connection = new MuHttpConnection(channel);
        // write to stdout all data sent by the client
        ByteBuffer res = ByteBuffer.allocate(10000);
        channel.read(res, null, new CompletionHandler<Integer, Object>() {
            @Override
            public void completed(Integer result, Object attachment) {
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
            public void failed(Throwable exc, Object attachment) {
                connections.remove(connection);
                var logIt = !(exc instanceof ClosedChannelException);
                if (logIt) { // TODO also log if requests are in progress
                    log.error("Read failed", exc);
                }
                try {
                    channel.close();
                } catch (IOException e) {
                    log.error("Error closing", e);
                    throw new RuntimeException(e);
                }
            }
        });
        return connection;
    }

    private static Thread createAcceptor(MuServerBuilder builder, SSLContext sslContext, AsynchronousTlsChannelGroup channelGroup, ServerSocketChannel serverSocket, ConcurrentHashMap.KeySetView<MuHttpConnection, Boolean> connections, int port) throws IOException {
        InetSocketAddress endpoint = builder.interfaceHost() == null ? new InetSocketAddress(port) : new InetSocketAddress(builder.interfaceHost(), port);
        serverSocket.socket().bind(endpoint);

        if (sslContext == null) {
            // HTTP endpoint
            return null;
        } else {
            // HTTPS endpoint

            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (!channelGroup.isShutdown()) {
                        try {
                            SocketChannel rawChannel = serverSocket.accept();
                            log.info("Accepted");
                            rawChannel.configureBlocking(false);

                            // create TlsChannel builder, combining the raw channel and the SSLEngine, using minimal options
                            TlsChannel tlsChannel = ServerTlsChannel.newBuilder(rawChannel, sslContext).build();

                            // build asynchronous channel, based in the TLS channel and associated with the global group.
                            AsynchronousByteChannel asyncTlsChannel =
                                new AsynchronousTlsChannel(channelGroup, tlsChannel, rawChannel);
                            connections.add(createMuConnection(asyncTlsChannel, connections, builder.handlers()));
                        } catch (Exception e) {
                            if (Thread.interrupted() || e instanceof AsynchronousCloseException) {
                                log.info("Acceptor stopped: " + channelGroup.isShutdown());
                            } else {
                                log.error("Error in channel", e);
                            }
                        }
                    }
                }


            }, "mu-acceptor");
            return thread;

        }
    }

    @Override
    public void stop() {
        log.info("Stopping");
        try {
            socketChannel.close();
            log.info("socket channel closed");
        } catch (IOException e) {
            log.warn("Error closing socket channel");
        }

        channelGroup.shutdown();
        try {
            for (MuHttpConnection connection : connections) {
                try {
                    connection.shutdown();
                } catch (IOException e) {
                    log.warn("Error shutting down " + connection, e);
                }
            }
            if (!channelGroup.awaitTermination(10, TimeUnit.SECONDS)) {
                log.warn("Nope");
                channelGroup.shutdownNow();
                if (!channelGroup.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.warn("Still no!!!");
                }
            }
            log.info("Stopped");
        } catch (InterruptedException e) {
            log.warn("Interrupted while stopping", e);
        }
//        accepterThread.interrupt();
        log.info("Stop completed");
    }

    @Override
    public URI uri() {
        return httpsUri();
    }

    @Override
    public URI httpUri() {
        return null;
    }

    @Override
    public URI httpsUri() {
        return URI.create("https://localhost:" + socketChannel.socket().getLocalPort());
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
}
