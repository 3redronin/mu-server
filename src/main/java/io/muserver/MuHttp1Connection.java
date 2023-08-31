package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.NotFoundException;
import javax.ws.rs.RedirectionException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

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


    static String getRelativeUrl(URI uriInHeaderLine) throws InvalidHttpRequestException, RedirectException {
        try {
            URI requestUri = uriInHeaderLine.normalize();
            if (requestUri.getScheme() == null && requestUri.getHost() != null) {
                throw new RedirectException(new URI(uriInHeaderLine.toString().substring(1)).normalize());
            }

            String s = requestUri.getRawPath();
            if (Mutils.nullOrEmpty(s)) {
                s = "/";
            } else {
                // TODO: consider a redirect if the URL is changed? Handle other percent-encoded characters?
                s = s.replace("%7E", "~")
                    .replace("%5F", "_")
                    .replace("%2E", ".")
                    .replace("%2D", "-")
                ;
            }
            String q = requestUri.getRawQuery();
            if (q != null) {
                s += "?" + q;
            }
            return s;
        } catch (RedirectException re) {
            throw re;
        } catch (Exception e) {
            if (log.isDebugEnabled()) log.debug("Invalid request URL " + uriInHeaderLine);
            throw new InvalidHttpRequestException(400, "400 Bad Request");
        }
    }

    public MuHttp1Connection(ConnectionAcceptor acceptor, AsynchronousSocketChannel channel, InetSocketAddress remoteAddress, InetSocketAddress localAddress, ByteBuffer readBuffer) {
        this.acceptor = acceptor;
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
        this.requestParser = new RequestParser(acceptor.muServer.maxUrlSize(), acceptor.muServer.maxRequestHeadersSize());
        this.readBuffer = readBuffer;
    }

    static void useCustomExceptionHandlerOrFireIt(MuExchange exchange, Throwable ex) {
        var server = (MuServer2) exchange.request.server();
        if (ex instanceof UserRequestAbortException) {
            exchange.abort(ex);
        } else {
            try {
                if (server.unhandledExceptionHandler != null && !(ex instanceof RedirectionException) && server.unhandledExceptionHandler.handle(exchange.request, exchange.response, ex)) {
                    exchange.response.end();
                } else {
                    exchange.onException(ex);
                }
            } catch (Throwable handlerException) {
                exchange.onException(handlerException);
            }
        }
    }


    void handshakeComplete(String protocol, String cipher) {
        this.httpsProtocol = protocol;
        this.cipher = cipher;
        acceptor.onConnectionEstablished(this);
        readyToRead(false);
    }

//    void onResponseCompleted(MuResponseImpl muResponse) {
//        MuExchange e = exchange;
//        if (e != null) {
//            e.onResponseCompleted();
//            if (e.state.endState()) {
//                exchange = null;
//            }
//            completeGracefulShutdownMaybe();
//        }
//    }

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

    private void handleNewRequest(NewRequest newRequest) {
        var data = new MuExchangeData(MuHttp1Connection.this, newRequest);
        String relativeUri;
        try {
            relativeUri = getRelativeUrl(newRequest.uri());
        } catch (InvalidHttpRequestException e) {
            // TODO handle this
            throw new RuntimeException(e);
        } catch (RedirectException e) {
            // TODO redirect it
            throw new RuntimeException(e);
        }

        var headers = newRequest.headers();

        if (headers.containsValue(HeaderNames.EXPECT, HeaderValues.CONTINUE, true)) {
            long proposedLength = headers.getLong(HeaderNames.CONTENT_LENGTH.toString(), -1L);
            // TODO don't block and do handle timeouts
            try {
                if (proposedLength > acceptor.muServer.maxRequestSize()) {
                    var responseHeaders = MuHeaders.responseHeaders();
                    responseHeaders.set(HeaderNames.CONTENT_LENGTH, 0);
                    channel.write(MuResponseImpl.http1HeadersBuffer(responseHeaders, HttpVersion.HTTP_1_1, 417, "Expectation Failed")).get();
                    return;
                } else {
                    channel.write(Mutils.toByteBuffer("HTTP/1.1 100 Continue\r\n\r\n")).get();
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e.getCause());
            }

        }

        var req = new MuRequestImpl(data, newRequest.method(), relativeUri, headers, newRequest.hasBody());
        var resp = new MuResponseImpl(data, channel);
        var exchange = new MuExchange(data, req, resp);
        this.exchange = exchange;
        data.exchange = exchange;
        onExchangeStarted(exchange);

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
                resp.end();
            }
        } catch (Throwable e) {
            useCustomExceptionHandlerOrFireIt(exchange, e);
        }
    }


    void readyToRead(boolean canReadFromMemory) {
        if (canReadFromMemory && readBuffer.hasRemaining()) {
            ConMessage msg;
            try {
                msg = requestParser.offer(readBuffer);
            } catch (InvalidRequestException e) {
                log.warn("Invalid HTTP request. Closing connection.", e);
                acceptor.onInvalidRequest(e);
                forceShutdown(e);
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
    /**
     * Read from socket completed
     */
    @Override
    public void completed(Integer result, Object attachment) {
        log.info("Con read completed: " + result);
        if (result != -1) {
            readBuffer.flip();
            acceptor.muServer.stats.onBytesRead(result); // TODO handle this differently?
            try {
                var msg = requestParser.offer(readBuffer);
                handleMessage(msg);
            } catch (InvalidRequestException e) {
                // todo write it back
                log.error("Invalid request parsing message", e);
                acceptor.onInvalidRequest(e);
                forceShutdown(e);
            }
        } else {
            log.info("Got EOF from client");
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

    <A> void write(ByteBuffer src, long timeout, TimeUnit unit, A attachment, CompletionHandler<Integer, ? super A> handler) {
        channel.write(src, timeout, unit, attachment, handler);
    }

    <A> void scatteringWrite(ByteBuffer[] srcs, int offset, int length, long timeout, TimeUnit unit, A attachment, CompletionHandler<Long, ? super A> handler) {
        channel.write(srcs, offset, length, timeout, unit, attachment, handler);
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
        } else {
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
