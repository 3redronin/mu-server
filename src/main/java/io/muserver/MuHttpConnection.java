package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tlschannel.async.ExtendedAsynchronousByteChannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousByteChannel;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

class MuHttpConnection implements HttpConnection {
    private static final Logger log = LoggerFactory.getLogger(MuHttpConnection.class);
    private final AsynchronousByteChannel channel;
    private final Instant startTime = Instant.now();

    public MuHttpConnection(AsynchronousByteChannel channel) {
        this.channel = channel;
    }

    @Override
    public String protocol() {
        return HttpVersion.HTTP_1_1.version();
    }

    @Override
    public boolean isHttps() {
        return ExtendedAsynchronousByteChannel.class.isAssignableFrom(channel.getClass());
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
        return null;
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
        return null;
    }

    @Override
    public Optional<Certificate> clientCertificate() {
        return Optional.empty();
    }

    void shutdown() throws IOException {
        log.info("Connection closing - " + channel.isOpen());
        channel.close();
        log.info("Connection closed: " + channel.isOpen());
    }
}
