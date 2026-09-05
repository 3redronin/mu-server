package io.muserver;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.concurrent.Future;

/**
 * <p>A class to handle the request and response handling when using asynchronous request handling.</p>
 * <p>To asynchronously read the request body, see {@link #setReadListener(RequestBodyListener)}. To
 * write data, this interface provides asynchronous write operations, or alternatively you can use the
 * blocking write operations on the original {@link MuResponse}.</p>
 */
public interface AsyncHandle {

    /**
     * <p>Sets a listener that will be notified when chunks of request data become available.</p>
     * <p>If this is not set, then the usual (blocking) request reading methods on the request object can be used.</p>
     * @param readListener The listener.
     * @throws IllegalStateException if request-body access has already been claimed
     */
    void setReadListener(RequestBodyListener readListener);

    /**
     * Rejects subsequent writes and completes the exchange after all accepted writes drain.
     */
    void complete();

    /**
     * Call this to indicate that the response is complete.
     * <p>A non-null error cancels queued writes and terminates active output before releasing its
     * buffers. If output has not begun, the exception handler may send a 500 response; otherwise
     * the HTTP/1 connection is closed or the HTTP/2 stream is reset. An active HTTP/2 socket
     * frame may require closing its connection. Write futures fail independently of callback delivery.
     * @param throwable an exception to log, or null if there was no problem
     */
    void complete(@Nullable Throwable throwable);

    /**
     * <p>Writes data to the response asynchronously.</p>
     * <p>Note that even in async mode it is possible to use the blocking write methods on the {@link MuResponse}</p>
     * <p>See {@link #write(ByteBuffer)} for an alternative that returns a future.</p>
     * <p>If {@link #complete()} or {@link #complete(Throwable)} has already been called, the callback is
     * invoked with an {@link IllegalStateException}.</p>
     * <p>Multiple submissions are supported and written in order. Mu owns each buffer until its
     * future completes or callback runs; do not modify or reuse it sooner. Waiting for completion
     * before submitting more data provides backpressure. Do not mix concurrent blocking writes
     * with these submissions. Callbacks use the application executor and can be dropped if it rejects.</p>
     * @param data The data to write
     * @param callback The callback when the write succeeds or fails
     */
    void write(ByteBuffer data, DoneCallback callback);

    /**
     * <p>Writes data to the response asynchronously.</p>
     * <p>Note that even in async mode it is possible to use the blocking write methods on the {@link MuResponse}</p>
     * <p>See {@link #write(ByteBuffer, DoneCallback)} for an alternative that uses a callback.</p>
     * <p>If {@link #complete()} or {@link #complete(Throwable)} has already been called, the returned future
     * fails with an {@link IllegalStateException}.</p>
     * <p>Multiple submissions are supported and written in order. Mu owns each buffer until its
     * future completes or callback runs; do not modify or reuse it sooner. Waiting for completion
     * before submitting more data provides backpressure. Do not mix concurrent blocking writes
     * with these submissions. Callbacks use the application executor and can be dropped if it rejects.</p>
     * @param data The data to write
     * @return A future resolved after I/O releases the buffer, independently of application workers.
     * Cancelling it cancels the asynchronous response and waits for active I/O to release its buffer.
     */
    Future<@Nullable Void> write(ByteBuffer data);

    /**
     * Add a listener for when request processing is complete. One use of this is to detect early client disconnects
     * so that expensive operations can be cancelled.
     * @param responseCompleteListener The handler to invoke when the request is complete.
     */
    void addResponseCompleteHandler(ResponseCompleteListener responseCompleteListener);

}
