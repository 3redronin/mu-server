package io.muserver;

/**
 * The current state of a request
 */
enum RequestState {
    /**
     * The request headers have been received. There is a request body but haven't started processing it yet.
     * <p>Note: if there is no request body expected, </p>
     */
    HEADERS_RECEIVED(false),
    /**
     * The request body is being received from the client
     */
    RECEIVING_BODY(false),
    /**
     * The full request was received
     */
    COMPLETE(true),
    /**
     * An error occurred before the full request was received, e.g. because the client disconnected or was uploading data too slowly
     */
    ERROR(true);
    private final boolean endState;

    RequestState(boolean endState) {
        this.endState = endState;
    }

    /**
     * @return True if the request has finished, either because the entire request has been received, or an error occurred
     * and request processing is cancelled
     */
    public boolean endState() {
        return endState;
    }
}

interface RequestStateChangeListener {
    void onChange(HttpExchange exchange, RequestState newState);
}
