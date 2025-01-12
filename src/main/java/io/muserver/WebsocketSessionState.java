package io.muserver;

import java.util.concurrent.TimeUnit;

/**
 * The state of a websocket session
 */
public enum WebsocketSessionState {

    /**
     * The session has not started yet
     */
    NOT_STARTED(false, false),
    /**
     * The session is running and messages can be written and received
     */
    OPEN(false, false),
    /**
     * The session was ended due to an unexpected error
     */
    ERRORED(true, false),

    /**
     * The session has ended as a result of the client sending a close frame
     */
    CLIENT_CLOSED(true, false),

    /**
     * The client has sent a close frame. The server is yet to close the connection on its side.
     */
    CLIENT_CLOSING(false, true),

    /**
     * The server has sent a close frame to the client, but the connection is not yet closed
     */
    SERVER_CLOSING(false, true),

    /**
     * The connection has ended due to a server-initiated shutdown
     */
    SERVER_CLOSED(true, false),

    /**
     * The connection was disconnected early (before a graceful shutdown could occur)
     */
    DISCONNECTED(true, false),

    /**
     * The session was disconnected because no messages were received in the time period configured
     * with {@link WebSocketHandlerBuilder#withIdleReadTimeout(int, TimeUnit)}
     */
    TIMED_OUT(true, false);

    private final boolean endState;
    private final boolean closing;

    WebsocketSessionState(boolean endState, boolean closing) {
        this.endState = endState;
        this.closing = closing;
    }

    /**
     * @return True if the websocket session has ended for any reason
     */
    public boolean endState() {
        return endState;
    }

    /**
     * @return True if the shutdown handshake has started (but not completed)
     */
    public boolean closing() {
        return closing;
    }
}
