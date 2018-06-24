package io.muserver;

/**
 * A callback for asynchronous write operations.
 */
public interface WriteCallback {

    /**
     * Called if the write failed, for example if the user closed their browser while writing to it
     * @param reason The reason for the failure
     */
    void onFailure(Throwable reason) throws Exception;

    /**
     * Called if the write succeeds
     */
    void onSuccess() throws Exception;

}
