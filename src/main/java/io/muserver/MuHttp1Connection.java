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
import java.util.Collections;
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
    private String httpsProtocol;
    private String cipher;
    private boolean inputClosed = false;
    private boolean outputClosed = false;

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
                    if (e.state.endState()) {
                        exchange = null;
                    }
                }
            }
        });

        this.readBuffer = readBuffer;
    }

    void handshakeComplete(String protocol, String cipher) {
        this.httpsProtocol = protocol;
        this.cipher = cipher;
        readyToRead();
    }

    void readyToRead() {
        channel.read(readBuffer, null, this);
    }

    void onResponseCompleted(MuResponseImpl muResponse) {
        MuExchange e = exchange;
        if (e != null) {
            e.onResponseCompleted();
            if (e.state.endState()) {
                exchange = null;
            }
            completeGracefulShutdownMaybe();
        }
    }

    @Override
    public String protocol() {
        return HttpVersion.HTTP_1_1.version();
    }

    @Override
    public boolean isHttps() {
        return channel instanceof MuTlsAsynchronousSocketChannel;
    }

    @Override
    public String httpsProtocol() {
        return httpsProtocol;
    }

    @Override
    public String cipher() {
        return cipher;
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
        MuExchange cur = this.exchange;
        return cur == null ? Collections.emptySet() : Set.of(cur.request);
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

    /**
     * Read from socket completed
     */
    @Override
    public void completed(Integer result, Object attachment) {
        log.info("Con read completed: " + result);
        if (result != -1) {
            readBuffer.flip();
            server.stats.onBytesRead(result);
            try {
                requestParser.offer(readBuffer);
                readBuffer.compact();
                readyToRead();
            } catch (InvalidRequestException e) {
                server.stats.onInvalidRequest();
                forceShutdown();
            }
        } else {
            log.info("Got EOF from client");
            inputClosed = true;
            completeGracefulShutdownMaybe();
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
        forceShutdown();
    }

    public void forceShutdown() {
        try {
            if (channel.isOpen()) {
                log.info("Server closing " + this);
                channel.close();
            }
        } catch (IOException e) {
            log.info("Error while closing channel: " + e.getMessage());
        } finally {
            server.onConnectionEnded(this);
        }
    }

    private void completeGracefulShutdownMaybe() {
        if (inputClosed && outputClosed) {
            forceShutdown();
        } else if (inputClosed && exchange == null) {
            log.info("No current exchange");
            initiateShutdown();
        }
    }

    public boolean initiateShutdown() {
        // Todo wait for active exchange
        if (!outputClosed) {
            outputClosed = true;
            try {
                log.info("Shutting down output stream");
                channel.shutdownOutput();
            } catch (IOException e) {
                forceShutdown();
                return false;
            }
        }
        completeGracefulShutdownMaybe();
        return true;
    }

    @Override
    public String toString() {
        String protocol = isHttps() ? "HTTPS" : "HTTP";
        String status = channel.isOpen() ? "Open" : "Closed";
        return status + " " + protocol + " 1.1 connection from " + remoteAddress;
    }
}
