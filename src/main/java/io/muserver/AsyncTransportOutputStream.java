package io.muserver;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;

/** Blocking public-stream adapter for an already-claimed asynchronous transport writer. */
final class AsyncTransportOutputStream extends OutputStream {
    private static final byte[] EMPTY = new byte[0];
    private final AsyncTransportOutput output;
    private final Runnable abort;
    private final ReentrantLock writers = new ReentrantLock();
    private boolean closed;

    AsyncTransportOutputStream(AsyncTransportOutput output, Runnable abort) {
        this.output = output;
        this.abort = abort;
    }

    @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}); }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        acquire();
        try {
            if (closed) throw new IOException("Transport output is closed");
            await(output.write(ByteBuffer.wrap(bytes, offset, length)));
        } finally { writers.unlock(); }
    }

    @Override public void flush() throws IOException { write(EMPTY); }

    @Override public void close() throws IOException {
        acquire();
        try {
            if (closed) return;
            await(output.write(ByteBuffer.wrap(EMPTY)));
            closed = true;
        } finally { writers.unlock(); }
    }

    private void acquire() throws IOException {
        try { writers.lockInterruptibly(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted(interrupted);
        }
    }

    private void await(CompletableFuture<?> written) throws IOException {
        try { written.get(); }
        catch (InterruptedException interrupted) {
            Throwable failedAbort = null;
            try { abort.run(); }
            catch (Throwable failure) { failedAbort = failure; }
            // The caller may reuse its array as soon as write returns. Even interruption
            // must join the transport's actual acknowledgement instead of cancelling it.
            for (;;) {
                try { written.get(); break; }
                catch (InterruptedException again) { /* Keep the borrow until acknowledgement. */ }
                catch (ExecutionException completed) { break; }
            }
            Thread.currentThread().interrupt();
            InterruptedIOException failure = interrupted(interrupted);
            if (failedAbort != null) {
                failure.addSuppressed(failedAbort);
                FatalErrors.rethrow(failedAbort);
            }
            throw failure;
        } catch (ExecutionException failure) {
            Throwable cause = Objects.requireNonNull(failure.getCause());
            if (cause instanceof IOException) throw (IOException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            FatalErrors.rethrow(cause);
            throw new IOException("Transport output failed", cause);
        }
    }

    private static InterruptedIOException interrupted(InterruptedException cause) {
        InterruptedIOException failure = new InterruptedIOException("Interrupted while writing transport output");
        failure.initCause(cause);
        return failure;
    }
}
