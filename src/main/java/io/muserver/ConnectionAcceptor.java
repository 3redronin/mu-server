package io.muserver;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;

class ConnectionAcceptor implements CompletionHandler<AsynchronousSocketChannel, MuServer2> {
    private final AsynchronousServerSocketChannel channel;

    final InetSocketAddress address;
    private final HttpsConfig httpsConfig;
    final URI uri;
    private volatile boolean stopped = false;

    ConnectionAcceptor(AsynchronousServerSocketChannel channel, InetSocketAddress address, HttpsConfig httpsConfig) {
        this.channel = channel;
        this.address = address;
        this.httpsConfig = httpsConfig;
        this.uri = URI.create("http" + (httpsConfig == null ? "" : "s") + "://localhost:" + address.getPort());
    }

    boolean acceptsHttp() {
        return httpsConfig == null;
    }

    boolean acceptsHttps() {
        return httpsConfig != null;
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
        readyToAccept(muServer); // todo Will a thrown exception cause this to be called in the failed callback too?
        InetSocketAddress remoteAddress;
        try {
            remoteAddress = (InetSocketAddress) channel.getRemoteAddress();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        var sslContext = httpsConfig == null ? null : httpsConfig.sslContext();
        if (sslContext == null) {
            MuHttp1Connection connection = new MuHttp1Connection(muServer, channel, remoteAddress, ByteBuffer.allocate(10000));
            muServer.onConnectionAccepted(connection);
            connection.readyToRead();
        } else {
            try {
                SSLEngine engine = sslContext.createSSLEngine();
                engine.setEnabledProtocols(httpsConfig.protocolsArray());
                engine.setEnabledCipherSuites(httpsConfig.cipherSuitesArray());
                engine.setUseClientMode(false);
                var appBuffer = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
                var netBuffer = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
                var tlsChannel = new MuTlsAsynchronousSocketChannel(channel, engine, appBuffer, netBuffer);
                MuHttp1Connection connection = new MuHttp1Connection(muServer, tlsChannel, remoteAddress, appBuffer);
                tlsChannel.beginHandshake(connection);
            } catch (SSLException e) {
                throw new RuntimeException("Error beginning handshake", e);
            }
        }

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
