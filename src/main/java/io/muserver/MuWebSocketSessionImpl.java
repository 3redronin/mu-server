package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

class MuWebSocketSessionImpl implements MuWebSocketSession, Exchange {
    static final byte[] PING_BYTES = {'m', 'u'};
    private static final Logger log = LoggerFactory.getLogger(MuWebSocketSessionImpl.class);

    private volatile boolean closeSent = false;

    private final ChannelHandlerContext ctx;
    final MuWebSocket muWebSocket;
    private final HttpConnection connection;

    MuWebSocketSessionImpl(ChannelHandlerContext ctx, MuWebSocket muWebSocket, HttpConnection connection) {
        this.ctx = ctx;
        this.muWebSocket = muWebSocket;
        this.connection = connection;
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

    @Override
    public void onMessage(ChannelHandlerContext ctx, Object msg) throws UnexpectedMessageException {
        if (!(msg instanceof WebSocketFrame)) {
            throw new UnexpectedMessageException(this, msg);
        }
        MuWebSocket muWebSocket = this.muWebSocket;
        DoneCallback onComplete = error -> {
            if (error == null) {
                ctx.channel().read();
            } else {
                handleWebsockError(ctx, muWebSocket, error);
            }
        };
        ByteBuf retained = null;
        try {
            if (msg instanceof TextWebSocketFrame) {
                muWebSocket.onText(((TextWebSocketFrame) msg).text(), onComplete);
            } else if (msg instanceof BinaryWebSocketFrame) {
                ByteBuf content = ((ByteBufHolder) msg).content();
                retained = content.retain();
                muWebSocket.onBinary(content.nioBuffer(), error -> {
                    content.release();
                    onComplete.onComplete(error);
                });
            } else if (msg instanceof PingWebSocketFrame) {
                ByteBuf content = ((ByteBufHolder) msg).content();
                retained = content.retain();
                muWebSocket.onPing(content.nioBuffer(), error -> {
                    content.release();
                    onComplete.onComplete(error);
                });
            } else if (msg instanceof PongWebSocketFrame) {
                ByteBuf content = ((ByteBufHolder) msg).content();
                retained = content.retain();
                muWebSocket.onPong(content.nioBuffer(), error -> {
                    content.release();
                    onComplete.onComplete(error);
                });
            } else if (msg instanceof CloseWebSocketFrame) {
                CloseWebSocketFrame cwsf = (CloseWebSocketFrame) msg;
                muWebSocket.onClientClosed(cwsf.statusCode(), cwsf.reasonText());
                onComplete.onComplete(null);
            }
        } catch (Throwable e) {
            if (retained != null) {
                retained.release();
            }
            handleWebsockError(ctx, muWebSocket, e);
        }
    }

    @Override
    public void onIdleTimeout(ChannelHandlerContext ctx, IdleStateEvent ise) {
        if (ise.state() == IdleState.READER_IDLE) {
            try {
                muWebSocket.onError(new TimeoutException("No messages received on websocket"));
            } catch (Exception e) {
                log.warn("Error while processing idle timeout", e);
                ctx.close();
            }
        } else if (ise.state() == IdleState.WRITER_IDLE) {
            sendPing(ByteBuffer.wrap(MuWebSocketSessionImpl.PING_BYTES), DoneCallback.NoOp);
        }

    }

    @Override
    public void onException(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof CorruptedFrameException) {
            try {
                muWebSocket.onError(new WebSocketProtocolException(cause.getMessage(), cause));
            } catch (Exception e) {
                ctx.channel().close();
            }
        }
    }

    @Override
    public void onConnectionEnded(ChannelHandlerContext ctx) {
        try {
            muWebSocket.onError(new ClientDisconnectedException());
        } catch (Exception ignored) {
        }
    }

    private void handleWebsockError(ChannelHandlerContext ctx, MuWebSocket muWebSocket, Throwable e) {
        try {
            muWebSocket.onError(e);
        } catch (Exception ex) {
            log.warn("Exception thrown by " + muWebSocket.getClass() + "#onError so will close connection", ex);
            ctx.close();
        }
    }


    @Override
    public HttpConnection connection() {
        return connection;
    }
}
