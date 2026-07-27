package io.muserver;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    void complete(@Nullable Throwable throwable);

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
    Future<@Nullable Void> write(ByteBuffer data);

    /**
     * Executes an application continuation for this exchange.
     *
     * <p>Handles supplied by Mu Server dispatch the task to the request-handler
     * execution domain when called from outside a Mu Server application callback.
     * Tasks submitted by an application callback stay in the same execution turn and
     * run after the current callback returns. This is useful when an asynchronous
     * result becomes available on an executor that should not perform response
     * serialization or invoke application callbacks.</p>
     *
     * <p>The default implementation runs the task immediately to retain compatibility
     * with custom implementations of this interface.</p>
     *
     * @param task The application continuation to execute
     */
    default void executeApplicationTask(Runnable task) {
        Objects.requireNonNull(task, "task").run();
    }

    /**
     * Executes an application continuation after a delay.
     *
     * <p>Handles supplied by Mu Server use the server timer only to determine when the
     * task is due, then dispatch the task to the request-handler execution domain. The
     * returned future can be cancelled before the delay expires to prevent dispatch.
     * Completion of the future does not guarantee that the application task has
     * finished.</p>
     *
     * <p>The default implementation uses the JDK delayed executor and delegates to
     * {@link #executeApplicationTask(Runnable)} to retain compatibility with custom implementations of
     * this interface.</p>
     *
     * @param task The application continuation to execute
     * @param delay The delay before execution
     * @param unit The unit of {@code delay}
     * @return A future representing the delayed execution
     */
    default Future<?> scheduleApplicationTask(Runnable task, long delay, TimeUnit unit) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unit, "unit");
        return CompletableFuture.runAsync(
            () -> executeApplicationTask(task),
            CompletableFuture.delayedExecutor(delay, unit)
        );
    }

    /**
     * Add a listener for when request processing is complete. One use of this is to detect early client disconnects
     * so that expensive operations can be cancelled.
     * @param responseCompleteListener The handler to invoke when the request is complete.
     */
    void addResponseCompleteHandler(ResponseCompleteListener responseCompleteListener);

}
