package io.muserver;

/**
 * A callback for listening to response completion events.
 * @see MuServerBuilder#addResponseCompleteListener(ResponseCompleteListener)
 */
public interface ResponseCompleteListener {

    /**
     * Called when a response completes (successfully or not).
     * @param info Information about the request and response.
     */
    void onComplete(ResponseInfo info);
}
