package io.muserver;

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
     */
    void setReadListener(RequestBodyListener readListener);

    /**
     * Call this to indicate that the response is complete.
     */
    void complete();

    /**
     * Call this to indicate that the response is complete.
     * <p>If the <code>throwable</code> parameter is not null then the error will be logged and, if possible,
     * a <code>500 Internal Server Error</code> message will be sent to the client.
     * @param throwable an exception to log, or null if there was no problem
     */
    void complete(Throwable throwable);

    /**
     * <p>Writes data to the response asynchronously.</p>
     * <p>Note that even in async mode it is possible to use the blocking write methods on the {@link MuResponse}</p>
     * <p>See {@link #write(ByteBuffer)} for an alternative that returns a future.</p>
     * @param data The data to write
     * @param callback The callback when the write succeeds or fails
     */
    void write(ByteBuffer data, DoneCallback callback);

    /**
     * <p>Writes data to the response asynchronously.</p>
     * <p>Note that even in async mode it is possible to use the blocking write methods on the {@link MuResponse}</p>
     * <p>See {@link #write(ByteBuffer, DoneCallback)} for an alternative that uses a callback.</p>
     * @param data The data to write
     * @return A future that is resolved when the write succeeds or fails.
     */
    Future<Void> write(ByteBuffer data);

    /**
     * Add a listener for when request processing is complete. One use of this is to detect early client disconnects
     * so that expensive operations can be cancelled.
     * @param responseCompleteListener The handler to invoke when the request is complete.
     */
    void addResponseCompleteHandler(ResponseCompleteListener responseCompleteListener);

}
