package io.muserver;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.concurrent.Future;

/**
 * Controls a response that can remain open after its request handler returns.
 * Obtain it from {@link MuRequest#handleAsync()} and call {@link #complete()} when finished.
 * <p>Use {@link #setReadListener(RequestBodyListener)} to read the request body asynchronously
 * and the write methods here to send the response asynchronously. Blocking request and response
 * methods are also available, but do not read the body in both ways or mix blocking and
 * asynchronous writes while a write is still in progress.</p>
 */
public interface AsyncHandle {

    /**
     * <p>Sets a listener that will be notified when chunks of request data become available.</p>
     * <p>If this is not set, then the usual (blocking) request reading methods on the request object can be used.</p>
     * <p>Callbacks run on the configured application executor. Call the completion callback supplied
     * with each chunk when that chunk is no longer needed; Mu can then reuse its buffer and read
     * the next chunk. Registering this listener prevents other request-body reading methods from being used.</p>
     * @param readListener The listener.
     * @throws IllegalStateException if request-body access has already been claimed
     */
    void setReadListener(RequestBodyListener readListener);

    /**
     * Finishes the response after all writes already submitted to this handle have finished.
     * Later writes are rejected. This method does not wait for those writes or their callbacks.
     */
    void complete();

    /**
     * Finishes the response, optionally reporting a failure. Passing null is equivalent to {@link #complete()}.
     * <p>A failure cancels writes that have not started and stops any write in progress before
     * returning its buffer to the application. If no response has been sent yet, the configured
     * exception handler may send HTTP 500. Otherwise Mu closes the HTTP/1 connection or resets
     * the HTTP/2 stream. If an HTTP/2 frame is partly transmitted, Mu may need to close that
     * connection as well to avoid corrupting other responses.</p>
     * @param throwable an exception to log, or null if there was no problem
     */
    void complete(@Nullable Throwable throwable);

    /**
     * Writes data to the response asynchronously and reports the result to a callback.
     * <p>You can submit several writes; Mu sends them in submission order. Do not change the
     * buffer's contents, position or limit until its callback runs. Submitting the next write
     * from that callback limits how much data waits in memory for a slow client.</p>
     * <p>The callback runs on the configured application executor with null on success or the
     * failure. Writes submitted after {@link #complete()} or {@link #complete(Throwable)} report
     * {@link IllegalStateException}. If a supplied executor rejects the callback, the notification
     * may not be delivered. See {@link #write(ByteBuffer)} for a future whose completion does not
     * require an application executor worker.</p>
     * @param data The data to write
     * @param callback The callback when the write succeeds or fails
     */
    void write(ByteBuffer data, DoneCallback callback);

    /**
     * Writes data to the response asynchronously and returns a future for the result.
     * <p>You can submit several writes; Mu sends them in submission order. Do not change the
     * buffer's contents, position or limit until its future finishes, successfully or otherwise.
     * Waiting for each write before submitting the next limits memory use with a slow client.
     * See {@link #write(ByteBuffer, DoneCallback)} for a callback-based alternative.</p>
     * <p>If {@link #complete()} or {@link #complete(Throwable)} has already been called, the returned future
     * fails with an {@link IllegalStateException}.</p>
     * <p>Cancelling the future cancels the whole asynchronous response. Cancellation waits for
     * any active write to stop using its buffer before returning.</p>
     * @param data The data to write
     * @return A future that finishes when the write succeeds or fails and Mu no longer uses its buffer.
     *         Completing this future does not require an application executor worker.
     */
    Future<@Nullable Void> write(ByteBuffer data);

    /**
     * Add a listener for when request processing is complete. One use of this is to detect early client disconnects
     * so that expensive operations can be cancelled.
     * @param responseCompleteListener The handler to invoke when the request is complete.
     */
    void addResponseCompleteHandler(ResponseCompleteListener responseCompleteListener);

}
