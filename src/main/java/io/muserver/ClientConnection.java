package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.Date;

class ClientConnection implements RequestParser.RequestListener {
    private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);


    private final RequestParser requestParser = new RequestParser(this);
    final ByteChannel channel;
    private HttpVersion httpVersion;
    private boolean isKeepAlive;

    ClientConnection(ByteChannel channel) {
        this.channel = channel;
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
    public void onHeaders(Method method, URI uri, HttpVersion httpVersion, MuHeaders headers, InputStream body) {
//        log.info(method + " " + uri + " " + httpVersion + " - " + headers);

        this.isKeepAlive = MuSelector.keepAlive(httpVersion, headers);
        this.httpVersion = httpVersion;
    }

    @Override
    public void onRequestComplete(MuHeaders trailers) {

//        log.info("Request complete. Trailers=" + trailers);
        MuHeaders respHeaders = new MuHeaders();
        respHeaders.set(HeaderNames.DATE.toString(), Mutils.toHttpDate(new Date()));
        respHeaders.set("X-Blah", "Ah haha");
        respHeaders.set("Content-Length", "0");


        ResponseGenerator resp = new ResponseGenerator(httpVersion);
        ByteBuffer toSend = resp.writeHeader(200, respHeaders);
        try {
            int written = channel.write(toSend);
            // TODO: while written?
        } catch (IOException e) {
            onClientClosed();
        }
    }


    void onClientClosed() {
        try {
            channel.close();
        } catch (IOException e) {
            log.info("Error closing connection, but the client had already closed.");
        }
    }
}
