package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

import static java.nio.charset.StandardCharsets.UTF_8;

class ClientConnection implements RequestParser.RequestListener {
    private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

    private final RequestParser requestParser;
    private final ExecutorService executorService;
    final ServerSettings settings;
    private final List<MuHandler> handlers;
    final ByteChannel channel;
    private final MuStatsImpl2 stats;
    private MuRequestImpl curReq;
    final String protocol;
    final InetAddress clientAddress;
    final MuServer server;
    private MuResponseImpl curResp;
    private AsyncHandleImpl asyncHandle;
    private AtomicLong bytesReceived = new AtomicLong(0);

    ClientConnection(ServerSettings settings, List<MuHandler> handlers, ByteChannel channel, String protocol, InetAddress clientAddress, MuServer server) {
        this.settings = settings;
        this.handlers = handlers;
        this.channel = channel;
        this.protocol = protocol;
        this.clientAddress = clientAddress;
        this.server = server;
        this.stats = (MuStatsImpl2) server.stats();
        this.stats.incrementActiveConnections();
        executorService = settings.executorService;
        requestParser = new RequestParser(settings.parserOptions, this);
    }

    static boolean keepAlive(HttpVersion version, MuHeaders headers) {
        List<String> connection = headers.getAll("connection");
        switch (version) {
            case HTTP_1_1:
                for (String value : connection) {
                    String[] split = value.split(",\\s*");
                    for (String s : split) {
                        if (s.equalsIgnoreCase("close")) {
                            return false;
                        }
                    }
                }
                return true;
            case HTTP_1_0:
                for (String value : connection) {
                    String[] split = value.split(",\\s*");
                    for (String s : split) {
                        if (s.equalsIgnoreCase("keep-alive")) {
                            return true;
                        }
                    }
                }
                return false;
        }
        throw new IllegalArgumentException(version + " is not supported");
    }


    void onBytesReceived(ByteBuffer buffer) {
        try {
            long l = bytesReceived.addAndGet(buffer.remaining());
            System.out.println("Total received on connection: " + l);
            stats.incrementBytesRead(buffer.remaining());
            requestParser.offer(buffer);
        } catch (Exception e) {
            // TODO tell the async stuff and stop processing
            try {

                if (e instanceof InvalidRequestException) {
                    InvalidRequestException ire = (InvalidRequestException) e;
                    stats.incrementInvalidHttpRequests();
                    ResponseGenerator rg = new ResponseGenerator(HttpVersion.HTTP_1_1);
                    MuHeaders headers = new MuHeaders();
                    headers.set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
                    headers.set(HeaderNames.DATE, Mutils.toHttpDate(new Date()));
                    headers.set(HeaderNames.CONTENT_TYPE, ContentTypes.TEXT_PLAIN);
                    String message = ire.responseCode + " " + ire.clientMessage;
                    ByteBuffer body = UTF_8.encode(message);
                    headers.set(HeaderNames.CONTENT_LENGTH, body.remaining());
                    log.warn("Invalid HTTP request detected from " + clientAddress + " - sent \"" + message + "\" to the client. Further info: " + ire.privateDetails);
                    channel.write(rg.writeHeader(ire.responseCode, headers));
                    channel.write(body);
                } else {
                    log.error("Error while parsing body", e);
                }
                channel.close();
            } catch (IOException e1) {
                log.info("Error closing channel after read error: " + e1.getMessage());
            }
        }
    }

    @Override
    public void onHeaders(Method method, URI uri, HttpVersion httpVersion, MuHeaders headers, GrowableByteBufferInputStream body) {
        MuRequestImpl req = new MuRequestImpl(method, uri, headers, body, this);
        boolean isKeepAlive = keepAlive(httpVersion, headers);
        MuResponseImpl resp = new MuResponseImpl(channel, req, isKeepAlive, stats, settings);
        asyncHandle = new AsyncHandleImpl(req, resp);
        req.setAsyncHandle(asyncHandle);
        stats.onRequestStarted(req);

        curReq = req;
        curResp = resp;

        executorService.submit(new Runnable() {
            @Override
            public void run() {

                boolean error = false;
                try {

                    boolean handled = false;
                    for (MuHandler muHandler : handlers) {
                        handled = muHandler.handle(req, resp);
                        if (handled) {
                            break;
                        }
                        if (req.isAsync()) {
                            throw new IllegalStateException(muHandler.getClass() + " returned false however this is not allowed after starting to handle a request asynchronously.");
                        }
                    }
                    if (!handled) {
                        send404(resp);
                    }


                } catch (Throwable ex) {
                    error = true;
                    dealWithUnhandledException(req, resp, ex);
                } finally {
                    if (error || !req.isAsync()) {
                        try {
                            resp.complete(error);
                        } catch (Throwable e) {
                            log.info("Error while completing request", e);
                        }
                    }
                }


            }
        });

    }

    @Override
    public void onRequestComplete(MuHeaders trailers) {
        asyncHandle.onReadComplete(null);
    }

    static void dealWithUnhandledException(MuRequest request, MuResponse response, Throwable ex) {
        if (response.hasStartedSendingData()) {
            log.warn("Unhandled error from handler for " + request + " (note that a " + response.status() +
                " was already sent to the client before the error occurred and so the client may receive an incomplete response)", ex);
        } else {
            String errorID = "ERR-" + UUID.randomUUID().toString();
            log.info("Sending a 500 to the client with ErrorID=" + errorID + " for " + request, ex);
            response.status(500);
            response.contentType(ContentTypes.TEXT_HTML);
            response.headers().set(HeaderNames.CONNECTION, HeaderValues.CLOSE);
            response.write("<h1>500 Internal Server Error</h1><p>ErrorID=" + errorID + "</p>");
        }
    }


    void onClientClosed() {
        stats.decrementActiveConnections();
        try {
            channel.close();
        } catch (IOException e) {
            log.info("Error closing connection, but the client had already closed.");
        }
    }

    static void send404(MuResponse resp) {
        resp.status(404);
        resp.contentType(ContentTypes.TEXT_PLAIN);
        resp.write("404 Not Found");
    }
}
