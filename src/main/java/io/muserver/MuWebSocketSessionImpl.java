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
        write(new TextWebSocketFrame(message));
    }

    @Override
    public void sendBinary(ByteBuffer message) {
        ByteBuf bb = Unpooled.wrappedBuffer(message);
        write(new BinaryWebSocketFrame(bb));
    }

    @Override
    public void sendPing(ByteBuffer payload) {
        ByteBuf bb = Unpooled.wrappedBuffer(payload);
        write(new PingWebSocketFrame(bb));
    }

    @Override
    public void sendPong(ByteBuffer payload) {
        ByteBuf bb = Unpooled.wrappedBuffer(payload);
        write(new PongWebSocketFrame(bb));
    }

    @Override
    public void close() {
        write(new CloseWebSocketFrame());
    }

    @Override
    public void close(int statusCode, String reason) {
        write(new CloseWebSocketFrame(statusCode, reason));
    }

    @Override
    public void disconnect() {
        ctx.close();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) ctx.channel().remoteAddress();
    }

    private void write(WebSocketFrame msg) {
        ctx.writeAndFlush(msg);
    }
}
