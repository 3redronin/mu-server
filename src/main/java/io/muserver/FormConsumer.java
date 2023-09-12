package io.muserver;

/**
 * A callback for asynchronous form reads
 */
public interface FormConsumer {

    /**
     * Called when the form read is complete
     * <p>Note that text fields will be in memory while file uploads are saved to disk</p>
     * @param form the form
     * @throws Exception Any exceptions thrown will be bubbled back to the client
     */
    void onReady(MuForm form) throws Exception;

    /**
     * Called if there is an error reading the form (e.g. the client disconnected before completing the request body upload)
     * @param cause The cause of the error
     */
    void onError(Throwable cause);

}
