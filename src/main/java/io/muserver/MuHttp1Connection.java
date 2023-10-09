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
import java.nio.channels.ClosedChannelException;
import java.nio.channels.CompletionHandler;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

class MuHttp1Connection implements HttpConnection, CompletionHandler<Integer, Void> {
    private static final Logger log = LoggerFactory.getLogger(MuHttp1Connection.class);
    final ConnectionAcceptor acceptor;
    private final MuSocketChannel channel;
    private final InetSocketAddress remoteAddress;
    private final InetSocketAddress localAddress;
    private final Instant startTime = Instant.now();
    private final RequestParser requestParser;
    volatile MuExchange exchange;
    private String httpsProtocol;
    private String cipher;
    private final AtomicLong completedRequests = new AtomicLong();
    private final AtomicLong rejectedDueToOverload = new AtomicLong();
    private Certificate clientCert;

    public MuHttp1Connection(ConnectionAcceptor acceptor, MuSocketChannel channel, InetSocketAddress remoteAddress, InetSocketAddress localAddress) {
        this.acceptor = acceptor;
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
        this.requestParser = new RequestParser(acceptor.muServer.maxUrlSize(), acceptor.muServer.maxRequestHeadersSize());
    }


    void handshakeComplete(String protocol, String cipher, Certificate clientCert) {
        this.httpsProtocol = protocol;
        this.cipher = cipher;
        this.clientCert = clientCert;
        acceptor.onConnectionEstablished(this);
        readyToRead();
    }

    @Override
    public String protocol() {
        return HttpVersion.HTTP_1_1.version();
    }

    @Override
    public boolean isHttps() {
        return channel instanceof AsyncTlsSocketChannel;
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
        return Optional.ofNullable(clientCert);
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
        log.info("Set exchange for " + exchange);
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
        channel.scatteringWrite(payload, 0, payload.length, new CompletionHandler<>() {
            @Override
            public void completed(Long result, Void attachment) {
                log.info("Sent " + status + " with " + result);
                if (responseHeaders.containsValue(HeaderNames.CONNECTION, HeaderValues.CLOSE, true)) {
                    initiateShutdown();
                } else {
                    readyToRead();
                }
            }
            @Override
            public void failed(Throwable exc, Void attachment) {
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


    void readyToRead() {
        var readBuffer = channel.readBuffer();
        if (readBuffer.hasRemaining()) {
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

        channel.read(this);
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
    public void completed(Integer result, Void attachment) {
        if (result != -1) {
            var readBuffer = channel.readBuffer();
            readBuffer.flip();
            acceptor.muServer.stats.onBytesRead(result); // TODO handle this differently?
            readyToRead();
        } else {
            if (exchange != null) {
                log.info("Got EOF from client when expecting more, so aborting " + exchange);
                exchange.abort(new EOFException("Client closed connection"));
            } else {
                log.info("Got EOF from client to shutting channel - status is " + channel.isOpen());
                initiateShutdown();
            }
        }
    }

    private void handleMessage(ConMessage msg) {
        Objects.requireNonNull(msg, "msg");
        if (msg instanceof NewRequest nr) {
            handleNewRequest(nr);
        } else {
            MuExchange e = exchange;
            if (e != null) {
                e.onMessage(msg);
            }
        }
    }

    void scatteringWrite(ByteBuffer[] srcs, int offset, int length, CompletionHandler<Long, Void> handler) {
        channel.scatteringWrite(srcs, offset, length, handler);
    }

    /**
     * Read from socket failed
     */
    @Override
    public void failed(Throwable exc, Void attachment) {
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
            channel.abort();
        } catch (IOException e) {
            log.info("Error while closing channel: " + e.getMessage());
        } finally {
            acceptor.onConnectionEnded(this, exc);
        }
    }

    public void initiateShutdown() {
        channel.close(err -> {
            if (err != null) {
                log.info("Ungraceful shutdown; killing it boz " + err.getMessage());
                forceShutdown(err);
            } else {
                log.info("Graceful shutdown complete");
                acceptor.onConnectionEnded(MuHttp1Connection.this, null);
            }
        });
    }

    @Override
    public String toString() {
        String protocol = isHttps() ? "HTTPS" : "HTTP";
        String status = channel.isOpen() ? "Open" : "Closed";
        return status + " " + protocol + " 1.1 connection from " + remoteAddress;
    }

    private void onExchangeStarted(MuExchange exchange) {
        log.info("On exchange started: " + exchange);
        this.exchange = exchange;
        acceptor.onExchangeStarted(exchange);
    }

    public void onExchangeComplete(MuExchange muExchange) {
        log.info("Exchange completed: " + muExchange);
        this.exchange = null;
        completedRequests.incrementAndGet();
        acceptor.onExchangeComplete(muExchange);
        if (muExchange.state == HttpExchangeState.ERRORED) {
            forceShutdown(null); // TODO put an exception?
        } else if (muExchange.response.headers().contains(HeaderNames.CONNECTION, HeaderValues.CLOSE, true)) {
            initiateShutdown();
        } else if (muExchange.state == HttpExchangeState.COMPLETE) {
            // todo is this the only time to read again?
            readyToRead();
        } else {
            log.error("Unexpected situation: " + muExchange);
        }
    }

}
