package io.muserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

/**
 * <p>A base class for server-side web sockets, that takes care of capturing the web socket session, responding
 * to pings, and closure events.</p>
 * <p>This is an alternative to implementing the {@link MuWebSocket} interface and is recommended so that any
 * additions to the interface are non-breaking to implementors.</p>
 */
public abstract class BaseWebSocket implements MuWebSocket {
    private MuWebSocketSession session;
    private volatile boolean closeSent = false;

    @Override
    public void onConnect(MuWebSocketSession session) throws Exception {
        this.session = session;
    }

    @Override
    public void onText(String message) throws Exception {
    }

    @Override
    public void onBinary(ByteBuffer buffer) throws Exception {
    }

    @Override
    public void onClientClosed(int statusCode, String reason) throws Exception {
        if (!closeSent) {
            closeSent = true;
            try {
                session.close(statusCode, reason);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void onPing(ByteBuffer payload) throws Exception {
        if (!closeSent) {
            session().sendPong(payload);
        }
    }

    @Override
    public void onPong(ByteBuffer payload) throws Exception {
    }

    @Override
    public void onError(Throwable cause) throws Exception {
        if (!closeSent) {
            if (cause instanceof TimeoutException) {
                session().close(1001, "Idle Timeout");
            } else if (cause instanceof WebSocketProtocolException) {
                // do nothing as it is already closed by Netty
            } else if (session != null) {
                session().close(1011, "Server error");
            }
        }
    }

    /**
     * Gets the websocket session
     * @return A session that can be used to send message and events to the client.
     * @throws IllegalStateException Thrown if the socket has not been connected yet.
     */
    protected MuWebSocketSession session() {
        if (session == null) {
            throw new IllegalStateException("The websocket has not been connected yet");
        } else if (closeSent) {
            throw new IllegalStateException("The session is no longer available as the close event has been sent.");
        }
        return session;
    }

}
