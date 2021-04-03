package io.muserver;

/**
 * Information about a request and response.
 * @see MuServerBuilder#addResponseCompleteListener(ResponseCompleteListener)
 */
public interface ResponseInfo {

    /**
     * The duration in millis of a completed response, or the duration so far of an in-progress request.
     * @return the number of milliseconds from the start of the request until the end of the response.
     */
    long duration();

    /**
     * Indicates whether or not a response completed successfully. Non-successful completion may be due to events
     * such as the client disconnecting early, or a response timing out.
     * <p>Note: even server errors such as a 500 are considered &quot;successful&quot; if the full response
     * was sent to the client.</p>
     * @return Returns true if the request was fully read and the response was fully sent to the client.
     */
    boolean completedSuccessfully();

    /**
     * @return The request
     */
    MuRequest request();

    /**
     * @return The response
     */
    MuResponse response();

}
