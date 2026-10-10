package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded plaintext output with blocking or asynchronous write completion and nonblocking drain.
 * A successful write means all its bytes were consumed by the supplied transport sink, not merely
 * copied into this buffer, and does not imply peer receipt. Blocking write/flush belongs on workers.
 * The exclusive asynchronous writer retains at most one source until its completion; transport
 * drain refills the ring without reserving a waiting worker. Internal futures only schedule work.
 * The internal output notification must schedule transport work without blocking or throwing.
 */
final class TransportOutputBuffer extends OutputStream {
    // Serialize whole writes, including capacity/completion waits, without a Java monitor.
    private final ReentrantLock writers = new ReentrantLock();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private byte[] bytes;
    private final Runnable outputAvailable;
    private int head;
    private int size;
    private boolean closed;
    private boolean asynchronous;
    private @Nullable PendingAsyncWrite pendingAsyncWrite;

    private static final class PendingAsyncWrite {
        final ByteBuffer source;
        final CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
        PendingAsyncWrite(ByteBuffer source) { this.source = source.duplicate(); }
    }

    /** Claim an idle buffer exclusively; prior blocking writes must have finished and cannot resume. */
    AsyncTransportOutput asynchronousWriter() throws IOException {
        lock.lock();
        try {
            ensureOpen();
            if (asynchronous || writers.isLocked() || size != 0) throw new IllegalStateException("Output already has a writer");
            asynchronous = true;
            return this::writeAsync;
        } finally { lock.unlock(); }
    }

    private CompletableFuture<@Nullable Void> writeAsync(ByteBuffer source) throws IOException {
        PendingAsyncWrite write = new PendingAsyncWrite(source);
        boolean empty;
        lock.lock();
        try {
            ensureOpen();
            if (pendingAsyncWrite != null) throw new IllegalStateException("An asynchronous write is still pending");
            empty = !source.hasRemaining();
            if (!empty) {
                pendingAsyncWrite = write;
                refillAsync();
            }
        } finally { lock.unlock(); }
        if (empty) write.completion.complete(null);
        else outputAvailable.run();
        return write.completion;
    }

    // Only the ring is passed to the sink. No caller storage is borrowed outside this lock.
    private void refillAsync() {
        PendingAsyncWrite write = pendingAsyncWrite;
        if (write == null) return;
        int count = Math.min(write.source.remaining(), bytes.length - size);
        int tail = (head + size) % bytes.length;
        int first = Math.min(count, bytes.length - tail);
        write.source.get(bytes, tail, first);
        write.source.get(bytes, 0, count - first);
        size += count;
    }

    private @Nullable IOException failure;

    TransportOutputBuffer(int capacity, Runnable outputAvailable) {
        if (capacity < 1) throw new IllegalArgumentException("Positive capacity required");
        bytes = new byte[capacity];
        this.outputAvailable = outputAvailable;
    }

    int pendingBytes() {
        lock.lock();
        try { return size; }
        finally { lock.unlock(); }
    }

    /**
     * One bounded write attempt to a NONBLOCKING sink. The sink must not retain the supplied buffer.
     * Zero/partial writes leave the unconsumed suffix queued. Abort serializes with this bounded call,
     * so queued storage cannot be reused while the sink accesses it. This never calls application code.
     * TLS needs a sink whose completion includes its corresponding encrypted output; SSLEngine.wrap
     * alone is not such a sink.
     */
    int drainTo(WritableByteChannel sink, int maxBytes) throws IOException {
        if (maxBytes < 1) throw new IllegalArgumentException("Positive drain budget required");
        PendingAsyncWrite finished = null;
        IOException failed = null;
        lock.lock();
        try {
            checkFailure();
            if (size == 0) return 0;
            int count;
            try {
                count = sink.write(ByteBuffer.wrap(bytes, head, Math.min(maxBytes, Math.min(size, bytes.length - head))));
            } catch (IOException | RuntimeException failedWrite) {
                failed = failedWrite instanceof IOException ? (IOException) failedWrite
                    : new IOException("Transport output failed", failedWrite);
                finished = failLocked(failed);
                throw failedWrite;
            }
            head = (head + count) % bytes.length;
            size -= count;
            refillAsync();
            if (size == 0 && pendingAsyncWrite != null) {
                finished = pendingAsyncWrite;
                pendingAsyncWrite = null;
            }
            if (count > 0) changed.signalAll();
            return count;
        } finally {
            lock.unlock();
            if (finished != null) {
                if (failed == null) finished.completion.complete(null);
                else finished.completion.completeExceptionally(failed);
            }
        }
    }

    /** Does not acquire the writer lock: it must release writers waiting for capacity or completion. */
    void fail(IOException cause) {
        PendingAsyncWrite finished;
        lock.lock();
        try { finished = failLocked(cause); }
        finally { lock.unlock(); }
        if (finished != null) finished.completion.completeExceptionally(cause);
    }

    private @Nullable PendingAsyncWrite failLocked(IOException cause) {
        if (failure == null) failure = cause;
        // The sink borrows ring storage under this lock. Release source ownership before completion.
        PendingAsyncWrite write = pendingAsyncWrite;
        pendingAsyncWrite = null;
        bytes = new byte[0];
        size = 0;
        changed.signalAll();
        return write;
    }

    @Override
    public void write(int value) throws IOException { write(new byte[]{(byte) value}); }

    @Override
    public void write(byte[] source, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, source.length);
        acquireWriter();
        try {
            int remaining = length;
            do {
                boolean notifyOutput = false;
                lock.lock();
                try {
                    awaitSizeBelow(bytes.length);
                    int count = Math.min(remaining, bytes.length - size);
                    notifyOutput = size == 0 && count > 0;
                    int tail = (head + size) % bytes.length;
                    int first = Math.min(count, bytes.length - tail);
                    System.arraycopy(source, offset, bytes, tail, first);
                    System.arraycopy(source, offset + first, bytes, 0, count - first);
                    size += count;
                    offset += count;
                    remaining -= count;
                } finally {
                    lock.unlock();
                    if (notifyOutput) outputAvailable.run();
                }
            } while (remaining > 0);
            awaitDrained();
        } finally { writers.unlock(); }
    }

    @Override
    public void flush() throws IOException {
        acquireWriter();
        try { awaitDrained(); }
        finally { writers.unlock(); }
    }

    @Override
    public void close() throws IOException {
        acquireWriter();
        try {
            lock.lock();
            try {
                if (closed) return;
                awaitSizeBelow(1);
                closed = true;
            } finally { lock.unlock(); }
        } finally { writers.unlock(); }
    }

    private void acquireWriter() throws IOException {
        try { writers.lockInterruptibly(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting to write transport output");
        }
        lock.lock();
        try {
            if (asynchronous) {
                writers.unlock();
                throw new IllegalStateException("The transport has an asynchronous writer");
            }
        } finally { lock.unlock(); }
    }

    private void awaitDrained() throws IOException {
        lock.lock();
        try { awaitSizeBelow(1); }
        finally { lock.unlock(); }
    }

    private void awaitSizeBelow(int limit) throws IOException {
        ensureOpen();
        while (size >= limit) {
            try { changed.await(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                // An interrupted write may already have emitted a prefix: never resume this stream.
                InterruptedIOException cause = new InterruptedIOException("Interrupted while waiting for transport output");
                fail(cause);
                throw cause;
            }
            ensureOpen();
        }
    }

    private void ensureOpen() throws IOException {
        checkFailure();
        if (closed) throw new IOException("Transport output closed");
    }

    private void checkFailure() throws IOException {
        if (failure != null) throw failure;
    }
}
