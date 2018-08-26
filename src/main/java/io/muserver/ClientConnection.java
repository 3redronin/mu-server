package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

class ClientConnection implements RequestParser.RequestListener {
    private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

    private final RequestParser requestParser = new RequestParser(this);
    private final ExecutorService executorService;
    private final List<MuHandler> handlers;
    final ByteChannel channel;
    private MuRequestImpl curReq;
    final String protocol;
    final InetAddress clientAddress;
    final MuServer server;
    private MuResponseImpl curResp;
    private AsyncHandleImpl asyncHandle;

    ClientConnection(ExecutorService executorService, List<MuHandler> handlers, ByteChannel channel, String protocol, InetAddress clientAddress, MuServer server) {
        this.executorService = executorService;
        this.handlers = handlers;
        this.channel = channel;
        this.protocol = protocol;
        this.clientAddress = clientAddress;
        this.server = server;
        log.info("New connection");
    }


    void onBytesReceived(ByteBuffer buffer) {
        try {
            requestParser.offer(buffer);
        } catch (Exception e) {
            // TODO tell the async stuff and stop processing
            log.error("Error while parsing body", e);

            if (e instanceof InvalidRequestException) {
                // send response
            }
            e.printStackTrace();
            try {
                channel.close();
            } catch (IOException e1) {
                log.info("Error closing channel after read error: " + e1.getMessage());
            }
        }
    }

    @Override
    public void onHeaders(Method method, URI uri, HttpVersion httpVersion, MuHeaders headers, GrowableByteBufferInputStream body) {
        MuRequestImpl req = new MuRequestImpl(method, uri, headers, body, this);
        boolean isKeepAlive = MuSelector.keepAlive(httpVersion, headers);
        MuResponseImpl resp = new MuResponseImpl(channel, req, isKeepAlive);
        asyncHandle = new AsyncHandleImpl(executorService, req, resp);
        req.setAsyncHandle(asyncHandle);

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
