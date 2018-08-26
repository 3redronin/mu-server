package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

class ClientConnection implements RequestParser.RequestListener {
    private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

    private final RequestParser requestParser = new RequestParser(this);
    final ByteChannel channel;
    private MuRequestImpl curReq;
    final String protocol;
    final InetAddress clientAddress;
    final MuServer server;
    private MuResponseImpl curResp;

    ClientConnection(ByteChannel channel, String protocol, InetAddress clientAddress, MuServer server) {
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
        MuResponseImpl resp = new MuResponseImpl(channel, httpVersion);
        curReq = req;
        curResp = resp;

    }

    @Override
    public void onRequestComplete(MuHeaders trailers) {
        curResp.write("Hello, world");
    }


    void onClientClosed() {
        try {
            channel.close();
        } catch (IOException e) {
            log.info("Error closing connection, but the client had already closed.");
        }
    }
}
