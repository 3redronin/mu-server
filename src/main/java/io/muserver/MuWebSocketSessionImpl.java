package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.websocketx.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

class MuWebSocketSessionImpl implements MuWebSocketSession {
    static final byte[] PING_BYTES = {'m', 'u'};
    private static final Logger log = LoggerFactory.getLogger(MuWebSocketSessionImpl.class);
    final ChannelPromise connectedPromise;

    private volatile boolean closeSent = false;

    private final ChannelHandlerContext ctx;
    final MuWebSocket muWebSocket;

    MuWebSocketSessionImpl(ChannelHandlerContext ctx, MuWebSocket muWebSocket, ChannelPromise channelPromise) {
        this.ctx = ctx;
        this.muWebSocket = muWebSocket;
        this.connectedPromise = channelPromise;
    }

    @Override
    public void sendText(String message, DoneCallback doneCallback) {
        writeAsync(new TextWebSocketFrame(message), doneCallback);
    }

    @Override
    public void sendBinary(ByteBuffer message, DoneCallback doneCallback) {
        ByteBuf bb = Unpooled.wrappedBuffer(message);
        writeAsync(new BinaryWebSocketFrame(bb), doneCallback);
    }

    @Override
    public void sendPing(ByteBuffer payload, DoneCallback doneCallback) {
        ByteBuf bb = Unpooled.wrappedBuffer(payload);
        writeAsync(new PingWebSocketFrame(bb), doneCallback);
    }

    @Override
    public void sendPong(ByteBuffer payload, DoneCallback doneCallback) {
        ByteBuf bb = Unpooled.wrappedBuffer(payload);
        writeAsync(new PongWebSocketFrame(bb), doneCallback);
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
            writeAsync(closeFrame, error -> {
                Http1Connection.clearWebSocket(ctx);
                ctx.close();
            });
        }
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) ctx.channel().remoteAddress();
    }

    private void writeAsync(WebSocketFrame msg, DoneCallback doneCallback) {

        if (closeSent && !(msg instanceof CloseWebSocketFrame)) {
            try {
                doneCallback.onComplete(new IllegalStateException("Writes are not allowed as the socket has already been closed"));
            } catch (Exception ignored) {
            }
        }
        ctx.channel()
            .writeAndFlush(msg)
            .addListener((ChannelFutureListener) future1 -> {
                try {
                    if (future1.isSuccess()) {
                        doneCallback.onComplete(null);
                    } else {
                        doneCallback.onComplete(future1.cause());
                    }
                } catch (Throwable e) {
                    log.warn("Unhandled exception from write callback", e);
                    close(1011, "Server error");
                }
            });
    }

}
