package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.NotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

class MuHttp1Connection implements HttpConnection, CompletionHandler<Integer, Object> {
    private static final Logger log = LoggerFactory.getLogger(MuHttp1Connection.class);
    private final MuServer2 server;
    private final AsynchronousSocketChannel channel;
    private final InetSocketAddress remoteAddress;
    private final Instant startTime = Instant.now();
    private final RequestParser requestParser;
    private final ByteBuffer readBuffer;
    volatile MuExchange exchange;

    public MuHttp1Connection(MuServer2 server, AsynchronousSocketChannel channel, InetSocketAddress remoteAddress, ByteBuffer readBuffer) {
        this.server = server;
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.requestParser = new RequestParser(new RequestParser.Options(8192, 8192), new RequestParser.RequestListener() {
            @Override
            public void onHeaders(Method method, URI uri, HttpVersion httpProtocolVersion, MuHeaders headers, GrowableByteBufferInputStream body) {
                var data = new MuExchangeData(server, MuHttp1Connection.this, httpProtocolVersion, headers);
                var req = new MuRequestImpl(data, method, uri, uri, headers);
                var resp = new MuResponseImpl(data, channel);
                exchange = new MuExchange(data, req, resp);
                server.stats.onRequestStarted(req);

                try {
                    boolean handled = false;
                    for (MuHandler muHandler : server.handlers) {
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
                MuExchange e = exchange;
                if (e != null) {
                    e.onRequestCompleted();
                }
            }
        });

        this.readBuffer = readBuffer;
    }

    void readyToRead() {
        log.info("HTTP1Connection reading");
        channel.read(readBuffer, null, this);
    }

    void onResponseCompleted(MuResponseImpl muResponse) {
        MuExchange e = exchange;
        if (e != null) {
            e.onResponseCompleted();
        }
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

    /**
     * Read from socket completed
     */
    @Override
    public void completed(Integer result, Object attachment) {
        if (result != -1) {
            readBuffer.flip();
            server.stats.onBytesRead(result);
            try {
                requestParser.offer(readBuffer);
                readBuffer.compact();
                readyToRead();
            } catch (InvalidRequestException e) {
                server.stats.onInvalidRequest();
                closeQuietly();
            }
        } else {
            closeQuietly();
        }
    }

    /**
     * Read from socket failed
     */
    @Override
    public void failed(Throwable exc, Object attachment) {
        server.onConnectionFailed(this, exc);
        var logIt = !(exc instanceof ClosedChannelException);
        if (logIt) { // TODO also log if requests are in progress
            log.error("Read failed", exc);
        }
        closeQuietly();
    }

    private void closeQuietly() {
        if (channel != null) {
            if (channel.isOpen()) {
                try {
                    channel.close(); // TODO just close outgoing?
                } catch (IOException e) {
                    log.error("Error closing", e);
                }
            }
        }
    }
}
