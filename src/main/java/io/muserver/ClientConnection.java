package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static io.muserver.MuServerHandler.dealWithUnhandledException;

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
        } catch (InvalidRequestException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onHeaders(Method method, URI uri, HttpVersion httpVersion, MuHeaders headers, GrowableByteBufferInputStream body) {
//        log.info(method + " " + uri + " " + httpVersion + " - " + headers);

        MuRequestImpl req = new MuRequestImpl(method, uri, headers, body, this);
        boolean isKeepAlive = MuSelector.keepAlive(httpVersion, headers);
        MuResponseImpl resp = new MuResponseImpl(channel, req, isKeepAlive);

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
