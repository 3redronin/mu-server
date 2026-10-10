package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded plaintext bridge from a transport producer to a blocking protocol/application reader.
 * The producer never waits for capacity: offer advances only across bytes copied into owned storage.
 * Internal capacity and input-readiness notifications must only schedule work, without blocking or throwing.
 * No application callbacks run here. A transport must stop reading when capacity reaches zero.
 */
final class TransportInputBuffer extends InputStream {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private byte[] bytes;
    private final Runnable capacityAvailable;
    private final Runnable inputAvailable;
    private int head;
    private int size;
    private int timeoutMillis;
    private boolean ended;
    private @Nullable IOException failure;
    private final InputReadiness readiness = new InputReadiness();

    TransportInputBuffer(int capacity, Runnable capacityAvailable) {
        this(capacity, capacityAvailable, () -> { });
    }

    TransportInputBuffer(int capacity, Runnable capacityAvailable, Runnable inputAvailable) {
        if (capacity < 1) throw new IllegalArgumentException("Positive capacity required");
        bytes = new byte[capacity];
        this.capacityAvailable = capacityAvailable;
        this.inputAvailable = inputAvailable;
    }

    int remainingCapacity() {
        lock.lock();
        try { return ended || failure != null ? 0 : bytes.length - size; }
        finally { lock.unlock(); }
    }

    int offer(ByteBuffer source) throws IOException {
        boolean notifyInput = false;
        CompletableFuture<Void> ready = null;
        lock.lock();
        try {
            checkFailure();
            if (ended) throw new IOException("Transport input has ended");
            int count = Math.min(source.remaining(), bytes.length - size);
            int tail = (head + size) % bytes.length;
            int first = Math.min(count, bytes.length - tail);
            source.get(bytes, tail, first);
            source.get(bytes, 0, count - first);
            notifyInput = size == 0 && count > 0;
            if (notifyInput) ready = readiness.take();
            size += count;
            if (count > 0) changed.signalAll();
            return count;
        } finally {
            lock.unlock();
            InputReadiness.signal(ready);
            if (notifyInput) inputAvailable.run();
        }
    }

    /** Buffered data remains readable before EOF. Input half-close does not close output. */
    void endOfInput() {
        boolean notifyInput;
        CompletableFuture<Void> ready;
        lock.lock();
        try {
            notifyInput = !ended && failure == null; ended = true; changed.signalAll();
            ready = readiness.take();
        }
        finally { lock.unlock(); }
        InputReadiness.signal(ready);
        if (notifyInput) inputAvailable.run();
    }

    /** Abort wins over queued bytes and EOF, and wakes blocked readers. The first failure is retained. */
    void fail(IOException cause) {
        boolean notifyInput;
        CompletableFuture<Void> ready;
        lock.lock();
        try {
            notifyInput = failure == null;
            if (notifyInput) failure = cause;
            // No ring storage is borrowed outside this lock; terminal failure can release it.
            bytes = new byte[0];
            size = 0;
            changed.signalAll();
            ready = readiness.take();
        } finally { lock.unlock(); }
        InputReadiness.signal(ready);
        if (notifyInput) inputAvailable.run();
    }

    /** Applies to reads started after this call, as with a socket read timeout. Zero disables it. */
    void readTimeoutMillis(int timeoutMillis) {
        if (timeoutMillis < 0) throw new IllegalArgumentException("Negative timeout");
        lock.lock();
        try { this.timeoutMillis = timeoutMillis; }
        finally { lock.unlock(); }
    }

    int readTimeoutMillis() {
        lock.lock();
        try { return timeoutMillis; }
        finally { lock.unlock(); }
    }

    CompletableFuture<Void> whenReadable() {
        lock.lock();
        try { return readiness.whenReadable(size > 0 || ended || failure != null); }
        finally { lock.unlock(); }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        return read(target, offset, length, true);
    }

    /** One nonblocking read: zero is temporarily empty; minus one is drained EOF. */
    int readAvailable(byte[] target) throws IOException {
        return readAvailable(target, 0, target.length);
    }

    int readAvailable(byte[] target, int offset, int length) throws IOException {
        return read(target, offset, length, false);
    }

    /** Includes terminal input, so a suspended protocol gets one last progression turn. */
    boolean readable() {
        lock.lock();
        try { return size > 0 || ended || failure != null; }
        finally { lock.unlock(); }
    }

    private int read(byte[] target, int offset, int length, boolean wait) throws IOException {
        Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) return 0;
        boolean notifyCapacity = false;
        lock.lock();
        try {
            boolean timed = timeoutMillis != 0;
            long remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (size == 0 && !ended && failure == null) {
                if (!wait) return 0;
                try {
                    if (!timed) changed.await();
                    else {
                        if (remainingNanos <= 0) throw new SocketTimeoutException("Transport read timed out");
                        remainingNanos = changed.awaitNanos(remainingNanos);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Interrupted while waiting for transport input");
                }
            }
            checkFailure();
            if (size == 0) return -1;
            notifyCapacity = size == bytes.length && !ended;
            int count = Math.min(length, size);
            int first = Math.min(count, bytes.length - head);
            System.arraycopy(bytes, head, target, offset, first);
            System.arraycopy(bytes, 0, target, offset + first, count - first);
            head = (head + count) % bytes.length;
            size -= count;
            return count;
        } finally {
            lock.unlock();
            if (notifyCapacity) capacityAvailable.run();
        }
    }

    @Override
    public int available() throws IOException {
        lock.lock();
        try { checkFailure(); return size; }
        finally { lock.unlock(); }
    }

    @Override
    public void close() { fail(new IOException("Transport input closed")); }

    private void checkFailure() throws IOException {
        if (failure != null) throw failure;
    }
}
