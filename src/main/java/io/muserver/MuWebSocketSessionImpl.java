package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.*;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

class MuWebSocketSessionImpl implements MuWebSocketSession {

    private final ChannelHandlerContext ctx;

    MuWebSocketSessionImpl(ChannelHandlerContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public void sendText(String message) {
        writeAndSync(new TextWebSocketFrame(message));
    }

    @Override
    public void sendBinary(ByteBuffer message) {
        ByteBuf bb = Unpooled.wrappedBuffer(message);
        writeAndSync(new BinaryWebSocketFrame(bb));
    }

    @Override
    public void sendPing(ByteBuffer payload) {
        ByteBuf bb = Unpooled.wrappedBuffer(payload);
        writeAndSync(new PingWebSocketFrame(bb));
    }

    @Override
    public void sendPong(ByteBuffer payload) {
        ByteBuf bb = Unpooled.wrappedBuffer(payload);
        writeAndSync(new PongWebSocketFrame(bb));
    }

    @Override
    public void close() {
        disconnect(new CloseWebSocketFrame());
    }

    @Override
    public void close(int statusCode, String reason) {
        if (statusCode < 1000 || statusCode >= 5000) {
            throw new IllegalArgumentException("Web socket closure codes must be between 1000 and 4999 (inclusive)");
        }
        disconnect(new CloseWebSocketFrame(statusCode, reason));
    }

    private void disconnect(CloseWebSocketFrame closeFrame) {
        try {
            writeAndSync(closeFrame);
        } finally {
            ctx.close();
        }
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) ctx.channel().remoteAddress();
    }

    private void writeAndSync(WebSocketFrame msg) {
        ctx.channel().writeAndFlush(msg).syncUninterruptibly();
    }
}
