package io.muserver;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A callback for async operations that calls when an operation completes.
 */
@NullMarked
public interface DoneCallback {

    /**
     * The operation has completed.
     *
     * @param error If <code>null</code>, then the operation was a success. Otherwise it failed.
     * @throws Exception The result of throwing an exception depends on the operation, and may include actions such as
     *                   disconnecting the client.
     */
    void onComplete(@Nullable Throwable error) throws Exception;

    /**
     * A callback that does nothing on completion.
     */
    DoneCallback NoOp = error -> {
    };

}
