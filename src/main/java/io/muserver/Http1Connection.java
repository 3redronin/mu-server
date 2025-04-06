package io.muserver;

import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.security.cert.Certificate;
import java.text.ParseException;
import java.time.Instant;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MessageBodyBit.EOFMsg;
import static java.util.Collections.emptySet;

class Http1Connection extends BaseHttpConnection {

    private static final Logger log = LoggerFactory.getLogger(Http1Connection.class);
    private final Queue<HttpRequestTemp> requestPipeline = new ConcurrentLinkedQueue<>();
    private final AtomicReference<@Nullable Object> currentRequest = new AtomicReference<>();
    protected HttpConnectionState state = HttpConnectionState.OPEN;

    Http1Connection(Mu3ServerImpl server, ConnectionAcceptor creator, Socket clientSocket, @Nullable Certificate clientCertificate, Instant handshakeStartTime) {
        super(server, creator, clientSocket, clientCertificate, handshakeStartTime);
    }

    @Override
    public void start(InputStream inputStream, OutputStream outputStream) {

        try {
            var requestParser = new Http1MessageParser(
                HttpMessageType.REQUEST,
                requestPipeline,
                inputStream,
                server.maxRequestHeadersSize(),
                server.maxUrlSize()
            );
            var closeConnection = false;
            while (!closeConnection) {
                Http1ConnectionMsg msg;
                try {
                    msg = requestParser.readNext();
                } catch (SocketTimeoutException ste) {
                    throw HttpException.requestTimeout();
                }
                if (msg == EOFMsg) {
                    log.info("EOF detected");
//                    reqStream.closeQuietly() // TODO: confirm if the input stream should be closed
                    clientSocket.shutdownInput();
                    break;
                }
                var request = (HttpRequestTemp)msg;

                var rejectException = request.getRejectRequest();
                String relativeUrl;
                try {
                    relativeUrl = request.normalisedUri();
                } catch (HttpException e) {
                    if (rejectException == null) {
                        rejectException = e;
                    }
                    relativeUrl = "/";
                }

                URI serverUri = creator.getUri().resolve(relativeUrl);
                URI requestUri = Headtils.getUri(log, request.headers(), relativeUrl, serverUri);
                Method method = request.getMethod();
                HttpVersion httpVersion = request.getHttpVersion();
                BodySize bodySize = request.getBodySize();
                assert method != null;
                assert httpVersion != null;
                assert bodySize != null;
                InputStream requestBody = (bodySize == BodySize.NONE) ? EmptyInputStream.INSTANCE : new Http1BodyStream(requestParser, server.getMaxRequestBodySize());
                var muRequest = new Mu3Request(this, method, requestUri, serverUri, httpVersion, request.headers(), bodySize, requestBody);
                clientSocket.setSoTimeout(requestTimeout);

                var muResponse = new Http1Response(muRequest, outputStream);
                muRequest.response = muResponse;
                closeConnection = muRequest.headers().closeConnectionRequested(httpVersion);

                if (rejectException == null) {
                    RateLimitRejectionAction first = null;
                    for (@NotNull RateLimiterImpl rateLimiter : server.getRateLimiters()) {
                        var action = rateLimiter.record(muRequest);
                        if (action != null && first == null) {
                            first = action;
                        }
                    }
                    if (first != null) {
                        if (first == RateLimitRejectionAction.SEND_429) {
                            rejectException = new HttpException(HttpStatus.TOO_MANY_REQUESTS_429);
                            rejectException.responseHeaders().setAll(request.headers());
                        }
                    }
                }

                if (rejectException != null) {
                    onInvalidRequest(rejectException);
                    muResponse.status(rejectException.status());
                    muResponse.headers().set(rejectException.responseHeaders());
                    if (rejectException.getMessage() != null) {
                        muResponse.write(rejectException.getMessage());
                    }
                    closeConnection = cleanUpNicely(closeConnection, muResponse, muRequest);
                } else {

                    onRequestStarted(muRequest);

                    try {
                        handleExchange(muRequest, muResponse);
                        closeConnection = cleanUpNicely(closeConnection, muResponse, muRequest);
                    } catch (Throwable e) {
                        closeConnection = true;
                        log.warn("Unrecoverable error for " + muRequest, e);
                        muResponse.setState(ResponseState.ERRORED);
                    } finally {
                        onExchangeEnded(muResponse);
                        clientSocket.setSoTimeout(0);
                    }
                    var websocket = muResponse.getWebsocket();
                    if (!closeConnection && websocket != null) {
                        currentRequest.set(websocket);
                        clientSocket.setSoTimeout(websocket.settings.idleReadTimeoutMillis);
                        websocket.runAndBlockUntilDone(inputStream, outputStream, requestParser.readBuffer);
                        closeConnection = true;
                    }
                }
                closeConnection = closeConnection || state != HttpConnectionState.OPEN;
            }
        } catch (Exception e) {
            // probably shouldn't log here so much for things like IO errors which would be common when clients disconnect
            log.error("Unhandled error at the socket", e);
        } finally {
            Mutils.closeSilently(clientSocket);
        }
    }

    private boolean cleanUpNicely(Boolean closeConnection, Http1Response muResponse, Mu3Request muRequest) {
        var reallyClose = closeConnection;
        if (!reallyClose) {
            reallyClose = muResponse.headers().closeConnectionRequested(muRequest.httpVersion());
        }
        try {
            if (!muRequest.cleanup()) {
                reallyClose = true;
            }
        } catch (Exception e) {
            reallyClose = true;
        }
        try {
            muResponse.cleanup();
        } catch (Exception e) {
            reallyClose = true;
        }
        return reallyClose;
    }

    @Override
    public void onRequestStarted(Mu3Request req) {
        currentRequest.set(req);
        super.onRequestStarted(req);
    }

    @Override
    public void onExchangeEnded(ResponseInfo exchange) {
        currentRequest.set(null);
        super.onExchangeEnded(exchange);
    }


    @Override
    public HttpVersion httpVersion() {
        return HttpVersion.HTTP_1_1;
    }

    @Override
    public Set<MuRequest> activeRequests() {
        var cur = currentRequest.get();
        return cur instanceof MuRequest ? Set.of((MuRequest) cur) : emptySet();
    }

    @Override
    public Set<MuWebSocket> activeWebsockets() {
        var cur = currentRequest.get();
        return cur instanceof MuWebSocket ? Set.of((MuWebSocket) cur) : emptySet();
    }

    @Override
    public void abort() throws IOException {
        if (closed.compareAndSet(false, true)) {
            var cur = currentRequest.get();
            if (cur instanceof Mu3Request) {
                Mu3AsyncHandleImpl asyncHandle = ((Mu3Request) cur).getAsyncHandle();
                if (asyncHandle != null) {
                    asyncHandle.complete(new MuException("Connection aborted"));
                }
            }
            clientSocket.close();
        }
    }

    @Override
    public void abortWithTimeout() throws IOException {
        if (closed.compareAndSet(false, true)) {
            var cur = currentRequest.get();
            if (cur != null) {
                if (cur instanceof Mu3Request) {
                    Mu3AsyncHandleImpl asyncHandle = ((Mu3Request) cur).getAsyncHandle();
                    if (asyncHandle != null) {
                        asyncHandle.complete(new TimeoutException("Idle timeout exceeded"));
                    }
                } else if (cur instanceof WebsocketConnection) {
                    ((WebsocketConnection)cur).onTimeout();
                }
            }
            clientSocket.close();
        }
    }


    @Override
    void initiateGracefulShutdown() {
        // TODO thread safety
        if (state == HttpConnectionState.OPEN) {
            state = HttpConnectionState.CLOSED_LOCAL;
        }
        if (isIdle()) {
            log.info("Connection is idle; shutting down");
            forceShutdown();
        } else if (!activeWebsockets().isEmpty()) {
            for (MuWebSocket activeWebsocket : activeWebsockets()) {
                try {
                    activeWebsocket.onServerShuttingDown();
                } catch (Exception e) {
                    log.info("Error while aborting websocket: {}", e.getMessage());
                    forceShutdown();
                }
            }
        }
    }

    @Override
    boolean isShutdown() {
        return state == HttpConnectionState.CLOSED;
    }

    @Override
    void forceShutdown() {
        // todo thread safety
        if (state == HttpConnectionState.CLOSED) {
            return;
        }
        try {
            clientSocket.close();
        } catch (IOException ignored) {
        } finally {
            state = HttpConnectionState.CLOSED;
        }

    }
}

