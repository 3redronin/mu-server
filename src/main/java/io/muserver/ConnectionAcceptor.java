package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.ConcurrentHashMap;

class ConnectionAcceptor implements CompletionHandler<AsynchronousSocketChannel, MuServer2> {
    private static final Logger log = LoggerFactory.getLogger(ConnectionAcceptor.class);
    private final AsynchronousServerSocketChannel acceptChannel;

    final InetSocketAddress address;

    private volatile HttpsConfig httpsConfig;
    private final ConcurrentHashMap.KeySetView<MuHttp1Connection, Boolean> connections = ConcurrentHashMap.newKeySet();

    final URI uri;
    private volatile boolean stopped = false;
    MuServer2 muServer;

    ConnectionAcceptor(AsynchronousServerSocketChannel channel, InetSocketAddress address, HttpsConfig httpsConfig) {
        this.acceptChannel = channel;
        this.address = address;
        this.httpsConfig = httpsConfig;
        this.uri = URI.create("http" + (httpsConfig == null ? "" : "s") + "://localhost:" + address.getPort());
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
        if (!stopped) {
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


            if (httpsConfig == null) {
                MuHttp1Connection connection = new MuHttp1Connection(this, channel, remoteAddress, address, ByteBuffer.allocate(10000));
                onConnectionEstablished(connection);
                connection.readyToRead();
            } else {
                var sslContext = httpsConfig.sslContext();
                SSLEngine engine = sslContext.createSSLEngine();
                engine.setSSLParameters(httpsConfig.sslParameters());
                engine.setUseClientMode(false);

                var appBuffer = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
                var netBuffer = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
                var tlsChannel = new MuTlsAsynchronousSocketChannel(channel, engine, appBuffer, netBuffer);

                MuHttp1Connection connection = new MuHttp1Connection(this, tlsChannel, remoteAddress, address, appBuffer);
                tlsChannel.beginHandshake(connection);
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
        if (!stopped) {
            readyToAccept(server);
            log.warn("Error while accepting socket", exc);
            server.stats.onFailedToConnect();
        }
    }

    public void stop() throws IOException {
        stopped = true;
        acceptChannel.close();
    }

    public void changeHttpsConfig(HttpsConfig newHttpsConfig) {
        this.httpsConfig = newHttpsConfig;
    }

    public void onExchangeStarted(MuExchange exchange) {
        muServer.onExchangeStarted(exchange);
    }

    public void onExchangeComplete(MuExchange exchange) {
        muServer.onExchangeComplete(exchange);
    }

    public void onInvalidRequest(InvalidRequestException e) {
        muServer.onInvalidRequest(e);
    }

    public void onConnectionEnded(MuHttp1Connection connection, Throwable exc) {
        if (connections.remove(connection)) {
            muServer.onConnectionEnded(connection);
        } else {
            log.warn("onConnectionEnded called again for " + connection, exc);
        }
    }
}
