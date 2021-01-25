package io.muserver;

/**
 * The state of a response
 */
enum ResponseState {
    /**
     * Nothing has started yet
     */
    NOTHING(false, false),

    /**
     * A non-chunked response has been successfully sent to the client, however the server-side processing has not completed yet
     */
    FULL_SENT(false, false),

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
     * An error (such as a disconnection from the client or an idle timeout) ocurred before the exchange was completed.
     */
    ERRORED(true, false),

    /**
     * Upgraded successfully to a websocket
     */
    UPGRADED(true, true);


    final boolean endState;
    final boolean fullResponseSent;

    ResponseState(boolean endState, boolean fullResponseSent) {
        this.endState = endState;
        this.fullResponseSent = fullResponseSent;
    }
}
