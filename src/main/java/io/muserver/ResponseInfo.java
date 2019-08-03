package io.muserver;

/**
 * Information about a request and response.
 * @see MuServerBuilder#addResponseCompleteListener(ResponseCompleteListener)
 */
public interface ResponseInfo {

    /**
     * @return Returns the number of milliseconds from the start of the request until the end of the response.
     */
    long duration();

    /**
     * Indicates whether or not a response completed successfully. Non-successful completion may be due to events
     * such as the client disconnecting early, or an unhandled exception being called.
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
