package io.muserver;

/**
 * A callback for asynchronous write operations.
 */
public interface WriteCallback {

    /**
     * Called if the write succeeds
     * @throws Exception thrown when the callback throws an exception, in which case the response will be closed.
     */
    void onSuccess() throws Exception;

    /**
     * Called if the write failed, for example if the user closed their browser while writing to it
     * @param reason The reason for the failure
     * @throws Exception thrown when the callback throws an exception, in which case the response will be closed.
     */
    void onFailure(Throwable reason) throws Exception;

    /**
     * A write callback that does nothing when it receives the callbacks
     */
    WriteCallback NoOp = new WriteCallback() {
        public void onSuccess() {}
        public void onFailure(Throwable reason) {}
    };
}

