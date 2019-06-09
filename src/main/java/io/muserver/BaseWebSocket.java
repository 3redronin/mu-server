package io.muserver;

import java.io.IOException;
import java.nio.ByteBuffer;

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
    public void onConnect(MuWebSocketSession session) {
        this.session = session;
    }

    @Override
    public void onText(String message) throws IOException {
    }

    @Override
    public void onBinary(ByteBuffer buffer) {
    }

    @Override
    public void onClose(int statusCode, String reason) {
        if (!closeSent) {
            closeSent = true;
            try {
                session.close(statusCode, reason);
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void onPing(ByteBuffer payload) throws IOException {
        if (!closeSent) {
            session().sendPong(payload);
        }
    }

    @Override
    public void onPong(ByteBuffer payload) {
    }

    @Override
    public void onIdleReadTimeout() throws Exception {
        if (!closeSent) {
            session().close(1001, "Idle Timeout");
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
