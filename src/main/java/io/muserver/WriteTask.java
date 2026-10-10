package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class WriteTask {
    private final LogicalHttp2Frame frame;
    private final @Nullable CountDownLatch completionCallback;
    private volatile @Nullable Exception error;
    private @Nullable CompletableFuture<@Nullable Void> completion;

    WriteTask(LogicalHttp2Frame frame, boolean waitable) {
        this.frame = frame;
        this.completionCallback = waitable ? new CountDownLatch(1) : null;
    }

    public LogicalHttp2Frame frame() {
        return frame;
    }

    private boolean writing;
    private boolean cancelled;
    private boolean finished;

    synchronized boolean beginWrite() {
        if (cancelled || finished) return false;
        writing = true;
        return true;
    }

    synchronized boolean isCancelled() { return cancelled; }

    /** Returns whether transport output must be aborted to release this task's buffer. */
    boolean cancel(IOException reason) {
        boolean abort;
        synchronized (this) {
            if (finished) return false;
            cancelled = true;
            if (error == null) error = reason;
            abort = writing;
            if (!writing) finish();
        }
        publishCompletion();
        return abort;
    }

    void finishPart(boolean last) {
        synchronized (this) {
            if (finished) return;
            writing = false;
            if (last || cancelled) finish();
        }
        publishCompletion();
    }

    public void complete() { finishPart(true); }

    /** Reject remaining output, retaining any fragment the transport still owns. */
    public void fail(Exception ex) {
        synchronized (this) {
            if (finished) return;
            cancelled = true;
            if (error == null) error = ex;
            if (!writing) finish();
        }
        publishCompletion();
    }

    /** The writer has stopped accessing the active fragment after a failure. */
    void writeFailed(Exception ex) {
        synchronized (this) {
            if (finished) return;
            writing = false;
            cancelled = true;
            if (error == null) error = ex;
            finish();
        }
        publishCompletion();
    }

    private void finish() {
        finished = true;
        if (completionCallback != null) completionCallback.countDown();
    }

    /** Internal acknowledgement only: callers must not cancel or complete this future. */
    CompletableFuture<@Nullable Void> completion() {
        CompletableFuture<@Nullable Void> result;
        synchronized (this) {
            if (completion == null) completion = new CompletableFuture<>();
            result = completion;
        }
        publishCompletion();
        return result;
    }

    private void publishCompletion() {
        CompletableFuture<@Nullable Void> target;
        Exception failure;
        synchronized (this) {
            if (!finished || completion == null) return;
            target = completion;
            failure = error;
        }
        // A listener can schedule the next write. Never run it under the task monitor.
        if (failure == null) target.complete(null);
        else target.completeExceptionally(failure);
    }

    void await() throws InterruptedException, IOException {
        if (completionCallback != null) completionCallback.await();
        throwIfFailed();
    }

    /** Buffer ownership cannot be released just because the waiting thread was interrupted. */
    void awaitTermination() {
        boolean interrupted = false;
        if (completionCallback != null) {
            for (;;) {
                try { completionCallback.await(); break; }
                catch (InterruptedException e) { interrupted = true; }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public void await(long timeout, TimeUnit unit) throws InterruptedException, IOException {
        if (completionCallback != null) {
            if (!completionCallback.await(timeout, unit)) {
                var tio = new IOException("Timed out waiting for completion callback");
                throw tio;
            }
        }
        throwIfFailed();
    }

    private void throwIfFailed() throws IOException {
        Exception err = error;
        if (err != null) {
            if (err instanceof IOException) {
                throw (IOException) err;
            } else if (err instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted waiting for completion callback");
            } else {
                throw new IOException("Error writing HTTP2 frame", err);
            }
        }
    }
}
