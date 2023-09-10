package io.muserver;

/**
 * A callback for async operations that calls when an operation completes.
 */
public interface DoneCallback {

    /**
     * The operation has completed.
     *
     * @param error If <code>null</code>, then the operation was a success. Otherwise it failed.
     */
    void onComplete(Throwable error);

    /**
     * A callback that does nothing on completion.
     */
    DoneCallback NoOp = error -> {
    };

}
