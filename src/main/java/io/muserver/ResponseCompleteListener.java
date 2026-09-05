package io.muserver;

/**
 * A callback for listening to response completion events.
 * <p>Callbacks run on the executor configured by {@link MuServerBuilder#withHandlerExecutor(java.util.concurrent.ExecutorService)}
 * after response processing and internal cleanup finish. Listeners added
 * to a response before it completes run in registration order. Listeners added after completion
 * have no ordering guarantee and may run concurrently. Callbacks may overlap subsequent requests
 * on the same HTTP/1 connection and need not run on the thread that handled the request.
 * Graceful shutdown waits for scheduled callbacks within its timeout. If a supplied executor
 * rejects a callback, that notification may not be delivered.</p>
 * @see MuServerBuilder#addResponseCompleteListener(ResponseCompleteListener)
 */
public interface ResponseCompleteListener {

    /**
     * Called when a response completes (successfully or not).
     * @param info Information about the request and response.
     */
    void onComplete(ResponseInfo info);
}
