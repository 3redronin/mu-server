package io.muserver;

import java.util.concurrent.TimeUnit;

/**
 * The state of a response
 */
public enum ResponseState {
    /**
     * Nothing has started yet
     */
    NOTHING(false, false),

    /**
     * The headers have been sent but not the body which is expected
     */
    HEADERS_SENT(false, false),

    /**
     * A non-chunked response has been successfully sent to the client
     */
    FULL_SENT(true, true),

    /**
     * Response body has started streaming, but is not complete
     */
    STREAMING(false, false),
    /**
     * Completion initiated. The final response part is being sent to the client.
     */
    FINISHING(false, false),
    /**
     * The full response was streamed to the client
     */
    FINISHED(true, true),

    /**
     * An error (such as an unhandled exception in a callback) occurred before the exchange was completed.
     */
    ERRORED(true, false),

    /**
     * The idle timeout (as specified in {@link MuServerBuilder#withIdleTimeout(long, TimeUnit)} occurred and so the
     * response was cancelled early.
     */
    TIMED_OUT(true, false),

    /**
     * The client disconnected before the full request and response was completed.
     */
    CLIENT_DISCONNECTED(true, false),

    /**
     * Upgraded successfully to a websocket
     */
    UPGRADED(true, true);


    private final boolean endState;
    private final boolean fullResponseSent;

    /**
     * @return True if the request and response has finished, either successfully or not.
     */
    public boolean endState() {
        return endState;
    }

    /**
     * @return True if the full response was sent to the client with no unexpected errors.
     */
    public boolean completedSuccessfully() {
        return fullResponseSent;
    }

    ResponseState(boolean endState, boolean fullResponseSent) {
        this.endState = endState;
        this.fullResponseSent = fullResponseSent;
    }
}

interface ResponseStateChangeListener {
    void onStateChange(HttpExchange exchange, ResponseState newState);
}
