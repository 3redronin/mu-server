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

    public void complete() {
        if (completionCallback != null) {
            completionCallback.countDown();
        }
    }

    public void fail(Exception ex) {
        if (completionCallback != null) {
            error = ex;
            completionCallback.countDown();
        }
    }

    public void await(long timeout, TimeUnit unit) throws InterruptedException, IOException {
        if (completionCallback != null) {
            if (!completionCallback.await(timeout, unit)) {
                var tio = new IOException("Timed out waiting for completion callback");
                error = tio;
                throw tio;
            }
        }
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
