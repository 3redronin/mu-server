package io.muserver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;

class MuWebSocketSessionImpl implements MuWebSocketSession, Exchange {
    static final byte[] PING_BYTES = {'m', 'u'};
    private static final Logger log = LoggerFactory.getLogger(MuWebSocketSessionImpl.class);
    private final ChannelHandlerContext ctx;
    final MuWebSocket muWebSocket;
    private final HttpConnection connection;
    private volatile WebsocketSessionState state = WebsocketSessionState.NOT_STARTED;
    private volatile ContinuationState receivingState = ContinuationState.NONE;
    private volatile ContinuationState sendingState = ContinuationState.NONE;

    enum ContinuationState {
        NONE, TEXT, BINARY
    }

    MuWebSocketSessionImpl(ChannelHandlerContext ctx, MuWebSocket muWebSocket, HttpConnection connection) {
        this.ctx = ctx;
        this.muWebSocket = muWebSocket;
        this.connection = connection;
    }

    @Override
    public void sendText(String message, DoneCallback doneCallback) {
        sendText(message, true, doneCallback);
    }

    @Override
    public void sendText(String message, boolean isLastFragment, DoneCallback doneCallback) {
        if (sendingState == ContinuationState.BINARY) {
            throw new IllegalStateException("Cannot send a text message while a partial binary message is being sent");
        }
        WebSocketFrame frame;
        if (sendingState == ContinuationState.NONE) {
            frame = new TextWebSocketFrame(isLastFragment, 0, message);
            if (!isLastFragment) {
                sendingState = ContinuationState.TEXT;
            }
        } else {
            frame = new ContinuationWebSocketFrame(isLastFragment, 0, message);
            if (isLastFragment) {
                sendingState = ContinuationState.NONE;
            }
        }
        writeAsync(frame, doneCallback);
    }

    @Override
    public void sendBinary(ByteBuffer message, DoneCallback doneCallback) {
        sendBinary(message, true, doneCallback);
    }

    @Override
    public void sendBinary(ByteBuffer message, boolean isLastFragment, DoneCallback doneCallback) {
        if (sendingState == ContinuationState.TEXT) {
            throw new IllegalStateException("Cannot send a binary message while a partial text message is being sent");
        }
        ByteBuf bb = Unpooled.wrappedBuffer(message);
        WebSocketFrame frame;
        if (sendingState == ContinuationState.NONE) {
            frame = new BinaryWebSocketFrame(isLastFragment, 0, bb);
            if (!isLastFragment) {
                sendingState = ContinuationState.BINARY;
            }
        } else {
            frame = new ContinuationWebSocketFrame(isLastFragment, 0, bb);
            if (isLastFragment) {
                sendingState = ContinuationState.NONE;
            }
        }
        writeAsync(frame, doneCallback);
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
        close(1000, "Server");
    }

    @Override
    public void close(int statusCode, String reason) {
        if (state.endState()) {
            throw new IllegalArgumentException("Cannot close a websocket when the state is " + state);
        }
        if (statusCode < 1000 || statusCode >= 5000) {
            throw new IllegalArgumentException("Web socket closure codes must be between 1000 and 4999 (inclusive)");
        }

        WebsocketSessionState endState;
        if (state == WebsocketSessionState.CLIENT_CLOSING) {
            endState = WebsocketSessionState.CLIENT_CLOSED;
        } else {
            setState(WebsocketSessionState.SERVER_CLOSING);
            if (statusCode == 1001 && WebsocketSessionState.TIMED_OUT.name().equals(reason)) {
                endState = WebsocketSessionState.TIMED_OUT;
            } else if (statusCode > 1000 && WebsocketSessionState.ERRORED.name().equals(reason)) {
                endState = WebsocketSessionState.ERRORED;
            } else {
                endState = WebsocketSessionState.SERVER_CLOSED;
            }
        }

        CloseWebSocketFrame closeFrame = new CloseWebSocketFrame(statusCode, reason);
        writeAsync(closeFrame, error -> ctx.close().addListener(future -> setState(future.isSuccess() ? endState : WebsocketSessionState.ERRORED)));
    }

    void setState(WebsocketSessionState newState) {
        this.state = newState;
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) ctx.channel().remoteAddress();
    }

    @Override
    public WebsocketSessionState state() {
        return state;
    }

    private void writeAsync(WebSocketFrame msg, DoneCallback doneCallback) {

        if (state.endState() || (state.closing() && !(msg instanceof CloseWebSocketFrame))) {
            try {
                doneCallback.onComplete(new IllegalStateException("Writes are not allowed as the socket has already been closed"));
                return;
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
    public void onMessage(ChannelHandlerContext ctx, Object msg, DoneCallback doneCallback) throws UnexpectedMessageException {
        if (!(msg instanceof WebSocketFrame)) {
            if (msg instanceof HttpContent) return; // upgrade requests always send a LastHttpComplete message that can be ignored. Any request body can be discarded too.
            throw new UnexpectedMessageException(this, msg);
        }
        WebSocketFrame frame = (WebSocketFrame) msg;
        if (state.endState() || state.closing()) {
            // https://tools.ietf.org/html/rfc6455#section-1.4
            // After sending a control frame indicating the connection should be
            // closed, a peer does not send any further data; after receiving a
            // control frame indicating the connection should be closed, a peer
            // discards any further data received.
            return;
        }
        MuWebSocket muWebSocket = this.muWebSocket;
        DoneCallback onComplete = error -> {
            if (error != null) {
                handleWebsocketError(ctx, muWebSocket, error);
            }
            doneCallback.onComplete(error);
        };
        ByteBuf retained = null;
        try {
            if (frame instanceof TextWebSocketFrame || (receivingState == ContinuationState.TEXT && frame instanceof ContinuationWebSocketFrame)) {
                receivingState = (frame.isFinalFragment()) ? ContinuationState.NONE : ContinuationState.TEXT;
                String content = frame.content().toString(StandardCharsets.UTF_8);
                muWebSocket.onText(content, frame.isFinalFragment(), onComplete);
            } else if (frame instanceof BinaryWebSocketFrame || (receivingState == ContinuationState.BINARY && frame instanceof ContinuationWebSocketFrame)) {
                receivingState = (frame.isFinalFragment()) ? ContinuationState.NONE : ContinuationState.BINARY;
                ByteBuf content = frame.content();
                retained = content.retain();
                muWebSocket.onBinary(content.nioBuffer(), frame.isFinalFragment(), error -> {
                    content.release();
                    onComplete.onComplete(error);
                });
            } else if (frame instanceof PingWebSocketFrame) {
                ByteBuf content = frame.content();
                retained = content.retain();
                muWebSocket.onPing(content.nioBuffer(), error -> {
                    content.release();
                    onComplete.onComplete(error);
                });
            } else if (frame instanceof PongWebSocketFrame) {
                ByteBuf content = frame.content();
                retained = content.retain();
                muWebSocket.onPong(content.nioBuffer(), error -> {
                    content.release();
                    onComplete.onComplete(error);
                });
            } else if (frame instanceof CloseWebSocketFrame) {
                CloseWebSocketFrame cwsf = (CloseWebSocketFrame) frame;
                if (state == WebsocketSessionState.SERVER_CLOSING) {
                    ctx.close().addListener(future -> setState(WebsocketSessionState.SERVER_CLOSED));
                } else {
                    setState(WebsocketSessionState.CLIENT_CLOSING);
                    muWebSocket.onClientClosed(cwsf.statusCode(), cwsf.reasonText());
                }
                onComplete.onComplete(null);
            }
        } catch (Throwable e) {
            if (retained != null) {
                retained.release();
            }
            handleWebsocketError(ctx, muWebSocket, e);
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
        } else {
            sendPing(ByteBuffer.wrap(MuWebSocketSessionImpl.PING_BYTES), DoneCallback.NoOp);
        }

    }

    @Override
    public boolean onException(ChannelHandlerContext ctx, Throwable cause) {
        if (!state.endState()) {
            try {
                muWebSocket.onError(cause);
                return false;
            } catch (Exception e) {
                return true;
            }
        }
        return true;
    }

    @Override
    public void onConnectionEnded(ChannelHandlerContext ctx) {
        if (!state.endState()) {
            setState(WebsocketSessionState.DISCONNECTED);
            try {
                muWebSocket.onError(new ClientDisconnectedException());
            } catch (Exception ignored) {
            }
        }
    }

    private void handleWebsocketError(ChannelHandlerContext ctx, MuWebSocket muWebSocket, Throwable e) {
        if (!state.endState()) {
            try {
                muWebSocket.onError(e);
            } catch (Exception ex) {
                log.warn("Exception thrown by " + muWebSocket.getClass() + "#onError so will close connection", ex);
                ctx.close();
            }
        }
    }


    @Override
    public HttpConnection connection() {
        return connection;
    }

    @Override
    public void onUpgradeComplete(ChannelHandlerContext ctx) {
        setState(WebsocketSessionState.OPEN);
        try {
            muWebSocket.onConnect(this);
        } catch (Exception e) {
            log.warn("Error thrown by websocket onComplete handler", e);
            ctx.fireUserEventTriggered(new MuExceptionFiredEvent(this, -1, e));
        }
    }
}

