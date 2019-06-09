package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

class MuWebSocketSessionImpl implements MuWebSocketSession {
    private static final Logger log = LoggerFactory.getLogger(MuWebSocketSessionImpl.class);

    private volatile boolean closeSent = false;

    private final ChannelHandlerContext ctx;
    final MuWebSocket muWebSocket;

    MuWebSocketSessionImpl(ChannelHandlerContext ctx, MuWebSocket muWebSocket) {
        this.ctx = ctx;
        this.muWebSocket = muWebSocket;
    }

    @Override
    public void sendText(String message) {
        writeSync(new TextWebSocketFrame(message));
    }

    @Override
    public void sendText(String message, WriteCallback writeCallback) {
        writeAsync(new TextWebSocketFrame(message), writeCallback);
    }

    @Override
    public void sendBinary(ByteBuffer message) {
        ByteBuf bb = Unpooled.wrappedBuffer(message);
        writeSync(new BinaryWebSocketFrame(bb));
    }

    @Override
    public void sendBinary(ByteBuffer message, WriteCallback writeCallback) {
        ByteBuf bb = Unpooled.wrappedBuffer(message);
        writeAsync(new BinaryWebSocketFrame(bb), writeCallback);
    }

    @Override
    public void sendPing(ByteBuffer payload) {
        ByteBuf bb = Unpooled.wrappedBuffer(payload);
        writeSync(new PingWebSocketFrame(bb));
    }

    @Override
    public void sendPong(ByteBuffer payload) {
        ByteBuf bb = Unpooled.wrappedBuffer(payload);
        writeSync(new PongWebSocketFrame(bb));
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
        if (!closeSent) {
            closeSent = true;
            try {
                writeSync(closeFrame);
            } finally {
                ctx.close();
            }
        }
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) ctx.channel().remoteAddress();
    }

    private void writeSync(WebSocketFrame msg) {
        write(msg).syncUninterruptibly();
    }

    private void writeAsync(WebSocketFrame msg, WriteCallback writeCallback) {
        ChannelFuture future = write(msg);
        future.addListener((ChannelFutureListener) future1 -> {
            try {
                if (future1.isSuccess()) {
                    writeCallback.onSuccess();
                } else {
                    writeCallback.onFailure(future1.cause());
                }
            } catch (Throwable e) {
                log.warn("Unhandled exception from write callback", e);
                close(1011, "Server error");
            }
        });
    }

    private ChannelFuture write(WebSocketFrame msg) {
        if (closeSent && !(msg instanceof CloseWebSocketFrame)) {
            throw new IllegalStateException("Writes are not allowed as the socket has already been closed");
        }
        return ctx.channel().writeAndFlush(msg);
    }
}
