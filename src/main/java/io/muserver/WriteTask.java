package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class WriteTask {
    private final LogicalHttp2Frame frame;
    private final @Nullable CountDownLatch completionCallback;
    private volatile @Nullable Exception error;

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
    synchronized boolean cancel(IOException reason) {
        if (finished) return false;
        cancelled = true;
        error = reason;
        if (!writing) finish();
        return writing;
    }

    synchronized void finishPart(boolean last) {
        writing = false;
        if (last || cancelled) finish();
    }

    public synchronized void complete() { finishPart(true); }

    public synchronized void fail(Exception ex) {
        if (finished) return;
        writing = false;
        error = ex;
        finish();
    }

    private void finish() {
        finished = true;
        if (completionCallback != null) completionCallback.countDown();
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
                error = tio;
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
