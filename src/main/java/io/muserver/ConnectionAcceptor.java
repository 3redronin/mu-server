package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

class ConnectionAcceptor implements CompletionHandler<AsynchronousSocketChannel, MuServer2> {
    private static final Logger log = LoggerFactory.getLogger(ConnectionAcceptor.class);
    private final AsynchronousServerSocketChannel acceptChannel;

    final InetSocketAddress address;

    private volatile HttpsConfig httpsConfig;
    private final ConcurrentHashMap.KeySetView<MuHttp1Connection, Boolean> connections = ConcurrentHashMap.newKeySet();

    final URI uri;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    MuServer2 muServer;

    ConnectionAcceptor(AsynchronousServerSocketChannel channel, InetSocketAddress address, HttpsConfig httpsConfig) {
        this.acceptChannel = channel;
        this.address = address;
        this.httpsConfig = httpsConfig;
        this.uri = URI.create("http" + (httpsConfig == null ? "" : "s") + "://localhost:" + address.getPort());
    }

    public int connectionCount() {
        return connections.size();
    }

    public HttpsConfig httpsConfig() {
        return httpsConfig;
    }

    boolean acceptsHttp() {
        return httpsConfig == null;
    }

    boolean acceptsHttps() {
        return httpsConfig != null;
    }

    public void readyToAccept(MuServer2 muServer) {
        if (this.muServer == null) {
            this.muServer = muServer;
        }
        if (!stopped.get()) {
            acceptChannel.accept(muServer, this);
        }
    }

    /**
     * A new connection has been accepted
     */
    @Override
    public void completed(AsynchronousSocketChannel channel, MuServer2 muServer) {
        readyToAccept(muServer);
        InetSocketAddress remoteAddress = null;
        try {
            remoteAddress = (InetSocketAddress) channel.getRemoteAddress();

            MuServerSettings settings = muServer.settings;
            if (httpsConfig == null) {
                ByteBuffer appReadBuffer = ByteBuffer.allocate(8192);
                appReadBuffer.limit(0);
                var plainTextSocketChannel = new AsyncPlaintextSocketChannel(appReadBuffer, settings.requestReadTimeoutMillis(), settings.responseWriteTimeoutMillis(), channel);
                MuHttp1Connection connection = new MuHttp1Connection(this, plainTextSocketChannel, remoteAddress, address);
                onConnectionEstablished(connection);
                connection.readyToRead();
            } else {
                var sslContext = httpsConfig.sslContext();
                SSLEngine engine = sslContext.createSSLEngine();
                engine.setSSLParameters(httpsConfig.sslParameters());
                engine.setUseClientMode(false);

                var appReadBuffer = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
                var appWriteBuffer = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
                var netReadBuffer = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
                var netWriterBuffer = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
                var tlsChannel = new AsyncTlsSocketChannel(channel, engine, appReadBuffer, netReadBuffer, appWriteBuffer, netWriterBuffer, settings.handshakeIOTimeout(), settings.requestReadTimeoutMillis(), settings.responseWriteTimeoutMillis());

                MuHttp1Connection connection = new MuHttp1Connection(this, tlsChannel, remoteAddress, address);
                tlsChannel.beginHandshake(error -> {
                    if (error == null) {
                        SSLSession session = engine.getSession();
                        appReadBuffer.position(appReadBuffer.limit());
                        connection.handshakeComplete(session.getProtocol(), session.getCipherSuite());
                    } else {
                        log.error("Error while handshaking", error);
                        connection.forceShutdown(error);
                    }
                });
            }
        } catch (Exception e) {
            if (channel.isOpen()) {
                log.info("Error accepting connection from " + (Mutils.coalesce(remoteAddress, "unknown client")) + ": " + e.getMessage());
                try {
                    channel.close();
                } catch (IOException ex) {
                    log.info("Error closing channel: " + ex.getMessage());
                }
            } else {
                log.warn("Error accepting socket; stopped=" + stopped, e);
            }
            muServer.stats.onFailedToConnect();
        }

    }

    void onConnectionEstablished(MuHttp1Connection connection) {
        connections.add(connection);
        muServer.onConnectionAccepted(connection);
    }


    @Override
    public void failed(Throwable exc, MuServer2 server) {
        if (!stopped.get()) {
            readyToAccept(server);
            log.warn("Error while accepting socket", exc);
            server.stats.onFailedToConnect();
        }
    }

    public void stopAccepting() throws IOException {
        if (stopped.compareAndSet(false, true)) {
            acceptChannel.close();
            for (MuHttp1Connection connection : connections) {
                if (connection.activeRequests().isEmpty()) {
                    log.info("Initiating graceful shutdown of " + connection);
                    connection.initiateShutdown();
                }
            }
        }
    }

    public void changeHttpsConfig(HttpsConfig newHttpsConfig) {
        this.httpsConfig = newHttpsConfig;
    }

    public void onExchangeStarted(MuExchange exchange) {
        muServer.onExchangeStarted(exchange);
    }

    public void onExchangeComplete(MuExchange exchange) {
        if (stopped.get()) {
            exchange.data.connection.initiateShutdown();
        }
        muServer.onExchangeComplete(exchange);
    }

    public void onInvalidRequest(InvalidRequestException e) {
        muServer.onInvalidRequest(e);
    }

    public void onConnectionEnded(MuHttp1Connection connection, Throwable exc) {
        if (connections.remove(connection)) {
            muServer.onConnectionEnded(connection);
        } else if (log.isDebugEnabled()) {
            log.debug("onConnectionEnded called again for " + connection, exc);
        }
    }

    public void killConnections() {
        for (MuHttp1Connection connection : connections) {
            connection.forceShutdown(null);
        }
    }

    public void onRejectedDueToOverload(RateLimitedException e) {
        muServer.onRejectedDueToOverload(e);
    }
}
