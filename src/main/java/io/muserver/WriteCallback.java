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
     * Creates a WriteCallback that executes the given runnable when complete (whether it is a failure or success).
     * @param runnable The thing to run on completion
     * @return A new callback
     */
    static WriteCallback whenComplete(Runnable runnable) {
        return new WriteCallback() {
            @Override
            public void onSuccess() {
                runnable.run();
            }
            @Override
            public void onFailure(Throwable reason) {
                runnable.run();
            }
        };
    }

    /**
     * A write callback that does nothing when it receives the callbacks
     */
    WriteCallback NoOp = new WriteCallback() {
        public void onSuccess() {}
        public void onFailure(Throwable reason) {}
    };
}

