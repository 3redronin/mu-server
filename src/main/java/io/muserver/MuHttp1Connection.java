package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.NotFoundException;
import java.io.EOFException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

class MuHttp1Connection implements HttpConnection, CompletionHandler<Integer, Object> {
    private static final Logger log = LoggerFactory.getLogger(MuHttp1Connection.class);
    final ConnectionAcceptor acceptor;
    private final AsynchronousSocketChannel channel;
    private final InetSocketAddress remoteAddress;
    private final InetSocketAddress localAddress;
    private final Instant startTime = Instant.now();
    private final RequestParser requestParser;
    private final ByteBuffer readBuffer;
    volatile MuExchange exchange;
    private String httpsProtocol;
    private String cipher;
    private boolean inputClosed = false;
    private boolean outputClosed = false;
    private boolean discardMode = false;
    private final AtomicLong completedRequests = new AtomicLong();
    private final AtomicLong rejectedDueToOverload = new AtomicLong();

    public MuHttp1Connection(ConnectionAcceptor acceptor, AsynchronousSocketChannel channel, InetSocketAddress remoteAddress, InetSocketAddress localAddress, ByteBuffer readBuffer) {
        this.acceptor = acceptor;
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
        this.requestParser = new RequestParser(acceptor.muServer.maxUrlSize(), acceptor.muServer.maxRequestHeadersSize());
        this.readBuffer = readBuffer;
    }


    void handshakeComplete(String protocol, String cipher) {
        this.httpsProtocol = protocol;
        this.cipher = cipher;
        acceptor.onConnectionEstablished(this);
        readyToRead(false);
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
    public InetSocketAddress localAddress() {
        return localAddress;
    }

    @Override
    public long completedRequests() {
        return completedRequests.get();
    }

    @Override
    public long invalidHttpRequests() {
        return 0;
    }

    @Override
    public long rejectedDueToOverload() {
        return rejectedDueToOverload.get();
    }

    @Override
    public Set<MuRequest> activeRequests() {
        MuExchange cur = this.exchange;
        return cur == null ? Collections.emptySet() : Set.of(cur.request);
    }

    @Override
    public Set<MuWebSocket> activeWebsockets() {
        return Collections.emptySet();
    }

    @Override
    public MuServer server() {
        return acceptor.muServer;
    }

    public URI serverUri() {
        int port = localAddress.getPort();
        String proto;
        if (isHttps()) {
            proto = "https";
            if (port == 443) port = -1;
        } else {
            proto = "http";
            if (port == 80) port = -1;
        }

        String s = proto + "://localhost";
        if (port != -1) s += ":" + port;
        return URI.create(s);
    }

    @Override
    public Optional<Certificate> clientCertificate() {
        return Optional.empty();
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    private void handleNewRequest(NewRequest newRequest) {
        var data = new MuExchangeData(MuHttp1Connection.this, newRequest);
        String relativeUri = newRequest.relativeUri();
        var headers = newRequest.headers();
        var req = new MuRequestImpl(data, newRequest.method(), relativeUri, headers, newRequest.hasBody());

        try {
            acceptor.muServer.settings.block(req);
        } catch (RateLimitedException e) {
            onRejectedDueToOverload(e);
            if (e.action == RateLimitRejectionAction.CLOSE_CONNECTION) {
                forceShutdown(e);
            } else {
                MuHeaders responseHeaders = MuHeaders.responseHeaders();
                responseHeaders.set(HeaderNames.RETRY_AFTER, e.retryAfterSeconds);
                writeSimpleResponseAsync(HttpStatusCode.TOO_MANY_REQUESTS_429, responseHeaders, "429 Too Many Requests");
            }
            return;
        }

        var resp = new MuResponseImpl(data);
        var exchange = new MuExchange(data, req, resp);
        this.exchange = exchange;
        data.exchange = exchange;
        onExchangeStarted(exchange);
        if (headers.containsValue(HeaderNames.EXPECT, HeaderValues.CONTINUE, true) && data.server().settings.autoHandleExpectHeaders()) {
            long proposedLength = headers.getLong(HeaderNames.CONTENT_LENGTH.toString(), -1L);
            if (proposedLength > acceptor.muServer.maxRequestSize()) {
                exchange.complete(new ClientErrorException("Expectation Failed - request body too large", 417));
            } else {
                exchange.sendInformationalResponse(HttpStatusCode.CONTINUE_100, error -> {
                    if (error == null) {
                        handleIt(req, resp, exchange);
                    } else {
                        exchange.complete(error);
                    }
                });
            }
        } else {
            handleIt(req, resp, exchange);
        }
    }

    private void onRejectedDueToOverload(RateLimitedException e) {
        rejectedDueToOverload.incrementAndGet();
        acceptor.onRejectedDueToOverload(e);
    }

    private void writeInvalidRequest(InvalidRequestException e) {
        log.info("Sending " + e.status + " because " + e.privateDetails);
        acceptor.onInvalidRequest(e);
        discardMode = true;
        var responseHeaders = MuHeaders.responseHeaders();
        responseHeaders.set(HeaderNames.CONTENT_TYPE, ContentTypes.TEXT_PLAIN_UTF8);
        responseHeaders.set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
        writeSimpleResponseAsync(e.status, responseHeaders, e.responseBody());
    }
    private void writeSimpleResponseAsync(HttpStatusCode status, MuHeaders responseHeaders, String bodyText) {
        var responseLine = ByteBuffer.wrap(status.http11ResponseLine());
        var body = bodyText == null ? null : Mutils.toByteBuffer(bodyText);
        int size = body == null ? 0 : body.remaining();
        if (!status.noContentLengthHeader()) {
            responseHeaders.set(HeaderNames.CONTENT_LENGTH, size);
        }
        var headers = MuResponseImpl.http1HeadersBuffer(responseHeaders);
        var payload = body == null || (requestParser.method() == Method.HEAD) ? new ByteBuffer[] { responseLine, headers } : new ByteBuffer[] { responseLine, headers, body };
        scatteringWrite(payload, 0, payload.length, null, new CompletionHandler<>() {
            @Override
            public void completed(Long result, Object attachment) {
                log.info("Sent " + status + " with " + result);
                readyToRead(true);
            }
            @Override
            public void failed(Throwable exc, Object attachment) {
                log.info("Failed to send " + status, exc);
                forceShutdown(exc);
            }
        });
    }

    private void handleIt(MuRequestImpl req, MuResponseImpl resp, MuExchange exchange) {
        try {
            boolean handled = false;
            for (MuHandler muHandler : acceptor.muServer.handlers) {
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
            if (!exchange.isAsync()) {
                exchange.complete();
            }
        } catch (Throwable e) {
            exchange.complete(e);
        }
    }


    void readyToRead(boolean canReadFromMemory) {
        if (canReadFromMemory && readBuffer.hasRemaining() && !discardMode) {
            ConMessage msg;
            try {
                msg = requestParser.offer(readBuffer);
            } catch (InvalidRequestException e) {
                writeInvalidRequest(e);
                return;
            } catch (RedirectException e) {
                writeRedirectResponse(e);
                return;
            }
            if (msg != null) {
                handleMessage(msg);
                return; // todo how about not returning and instead get ready to read more?
            }
            // todo if there is a partial body, was that lost?
            readBuffer.compact(); // todo only do it here?
        } else {
            readBuffer.clear();
        }

        channel.read(readBuffer, this.server().requestIdleTimeoutMillis(), TimeUnit.MILLISECONDS, null, this);
    }

    private void writeRedirectResponse(RedirectException e) {
        var responseHeaders = MuHeaders.responseHeaders();
        responseHeaders.set(HeaderNames.LOCATION, e.location.toString());
        writeSimpleResponseAsync(HttpStatusCode.PERMANENT_REDIRECT_308, responseHeaders, null);
    }

    /**
     * Read from socket completed
     */
    @Override
    public void completed(Integer result, Object attachment) {
        log.info("Con read completed: " + result);
        if (result != -1) {
            readBuffer.flip();
            acceptor.muServer.stats.onBytesRead(result); // TODO handle this differently?
            if (!discardMode) {
                try {
                    var msg = requestParser.offer(readBuffer);
                    handleMessage(msg);
                } catch (InvalidRequestException e) {
                    writeInvalidRequest(e);
                } catch (RedirectException e) {
                    writeRedirectResponse(e);
                }
            } else {
                readyToRead(false);
            }
        } else {
            log.info("Got EOF from client");
            if (exchange != null) {
                exchange.abort(new EOFException("Client closed connection"));
            }
            inputClosed = true;
            completeGracefulShutdownMaybe();
        }
    }

    private void handleMessage(ConMessage msg) {
        if (msg instanceof NewRequest nr) {
            handleNewRequest(nr);
        } else if (msg == null) {
            readBuffer.compact();
            if (readBuffer.hasRemaining()) {
                log.info("Going to read into the remaining " + readBuffer.remaining() + " in the hope of getting a full message");
            } else {
                // TODO get a  bigger buffer
                throw new RuntimeException("Buffer is full and there is no message!");
            }
            readyToRead(false);
        } else {
            MuExchange e = exchange;
            if (e != null) {
                e.onMessage(msg);
            }
        }
    }

    <A> void scatteringWrite(ByteBuffer[] srcs, int offset, int length, A attachment, CompletionHandler<Long, ? super A> handler) {
        var timeout = this.acceptor.muServer.settings.responseWriteTimeoutMillis();
        channel.write(srcs, offset, length, timeout, TimeUnit.MILLISECONDS, attachment, handler);
    }

    /**
     * Read from socket failed
     */
    @Override
    public void failed(Throwable exc, Object attachment) {
        MuExchange cur = exchange;
        if (cur != null) {
            log.warn("Killing exchange due to read error: " + cur, exc);
            cur.abort(exc);
        } else if (!(exc instanceof ClosedChannelException)) {
            log.warn("Read failure without an exchange", exc);
        }
        forceShutdown(exc);
    }

    public void forceShutdown(Throwable exc) {
        try {
            if (channel.isOpen()) {
                log.info("Server closing " + this);
                channel.close();
            }
        } catch (IOException e) {
            log.info("Error while closing channel: " + e.getMessage());
        } finally {
            acceptor.onConnectionEnded(this, exc);
        }
    }

    private void completeGracefulShutdownMaybe() {
        if (inputClosed && outputClosed) {
            log.info("This is a graceful shutdown");
            forceShutdown(null);
        } else if (inputClosed && exchange == null) {
            log.info("No current exchange");
            initiateShutdown();
        }
    }

    public void initiateShutdown() {
        // Todo wait for active exchange
        if (!outputClosed) {
            outputClosed = true;
            if (channel instanceof MuTlsAsynchronousSocketChannel tlsC) {
                log.info("Initiating graceful shutdown");
                tlsC.shutdownOutputAsync(new CompletionHandler<Void, Void>() {
                    @Override
                    public void completed(Void result, Void attachment) {
                        log.info("Outbound is closed and inputClosed=" + inputClosed);
                        inputClosed = true;
                        completeGracefulShutdownMaybe();
                    }

                    @Override
                    public void failed(Throwable exc, Void attachment) {
                        log.info("Graceful shutdown failed; closing: " + exc.getClass());
                        tlsC.closeQuietly();
                        acceptor.onConnectionEnded(MuHttp1Connection.this, exc); // TODO confirm this
                    }
                }, null);
            } else {
                log.info("Closing http connection");
                try {
                    channel.shutdownOutput();
                } catch (IOException ignored) {
                }
                forceShutdown(null);
            }
        }
    }

    @Override
    public String toString() {
        String protocol = isHttps() ? "HTTPS" : "HTTP";
        String status = channel.isOpen() ? "Open" : "Closed";
        return status + " " + protocol + " 1.1 connection from " + remoteAddress;
    }

    private void onExchangeStarted(MuExchange exchange) {
        this.exchange = exchange;
        acceptor.onExchangeStarted(exchange);
    }

    public void onExchangeComplete(MuExchange muExchange) {
        this.exchange = null;
        completedRequests.incrementAndGet();
        acceptor.onExchangeComplete(muExchange);
        if (muExchange.state == HttpExchangeState.ERRORED) {
            forceShutdown(null); // TODO put an exception?
        } else if (muExchange.response.headers().contains(HeaderNames.CONNECTION, HeaderValues.CLOSE, true)) {
            initiateShutdown();
        } else if (muExchange.state == HttpExchangeState.COMPLETE) {
            // todo is this the only time to read again?
            readyToRead(true);
        } else {
            log.error("Unexpected situation: " + muExchange);
        }
    }

}
