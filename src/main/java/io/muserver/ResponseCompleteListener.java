package io.muserver;

/**
 * A callback for listening to response completion events.
 * <p>Callbacks run on the application executor after internal exchange cleanup, in registration
 * order for each response. They may overlap subsequent requests on the same HTTP/1 connection
 * and have no same-thread guarantee. Accepted callbacks are included in the graceful shutdown
 * deadline; delivery can be dropped if a caller-supplied application executor rejects.</p>
 * @see MuServerBuilder#addResponseCompleteListener(ResponseCompleteListener)
 */
public interface ResponseCompleteListener {

    /**
     * Called when a response completes (successfully or not).
     * @param info Information about the request and response.
     */
    void onComplete(ResponseInfo info);
}
