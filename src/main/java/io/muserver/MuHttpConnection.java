package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousSocketChannel;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

class MuHttpConnection implements HttpConnection {
    private static final Logger log = LoggerFactory.getLogger(MuHttpConnection.class);
    private final MuServer2 server;
    private final AsynchronousSocketChannel channel;
    private final InetSocketAddress remoteAddress;
    private final Instant startTime = Instant.now();

    public MuHttpConnection(MuServer2 server, AsynchronousSocketChannel channel, InetSocketAddress remoteAddress) {
        this.server = server;
        this.channel = channel;
        this.remoteAddress = remoteAddress;
    }

    @Override
    public String protocol() {
        return HttpVersion.HTTP_1_1.version();
    }

    @Override
    public boolean isHttps() {
        throw new RuntimeException("not implemented");
    }

    @Override
    public String httpsProtocol() {
        return null;
    }

    @Override
    public String cipher() {
        return null;
    }

    @Override
    public Instant startTime() {
        return startTime;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    @Override
    public long completedRequests() {
        return 0;
    }

    @Override
    public long invalidHttpRequests() {
        return 0;
    }

    @Override
    public long rejectedDueToOverload() {
        return 0;
    }

    @Override
    public Set<MuRequest> activeRequests() {
        return null;
    }

    @Override
    public Set<MuWebSocket> activeWebsockets() {
        return null;
    }

    @Override
    public MuServer server() {
        return server;
    }

    @Override
    public Optional<Certificate> clientCertificate() {
        return Optional.empty();
    }

    void shutdown() throws IOException {
        log.info("Connection closing - " + channel.isOpen());
        if (channel.isOpen()) {
            channel.shutdownInput();
            channel.shutdownOutput();
            channel.close();
        }
        log.info("Connection closed: " + channel.isOpen());
    }
}
