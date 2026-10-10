package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded plaintext bridge from a transport producer to a blocking protocol/application reader.
 * The producer never waits for capacity: offer advances only across bytes copied into owned storage.
 * The internal capacity notification must only schedule transport work, without blocking or throwing.
 * No application callbacks run here. A transport must stop reading when capacity reaches zero.
 */
final class TransportInputBuffer extends InputStream {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final byte[] bytes;
    private final Runnable capacityAvailable;
    private int head;
    private int size;
    private int timeoutMillis;
    private boolean ended;
    private @Nullable IOException failure;

    TransportInputBuffer(int capacity, Runnable capacityAvailable) {
        if (capacity < 1) throw new IllegalArgumentException("Positive capacity required");
        bytes = new byte[capacity];
        this.capacityAvailable = capacityAvailable;
    }

    int remainingCapacity() {
        lock.lock();
        try { return ended || failure != null ? 0 : bytes.length - size; }
        finally { lock.unlock(); }
    }

    int offer(ByteBuffer source) throws IOException {
        lock.lock();
        try {
            checkFailure();
            if (ended) throw new IOException("Transport input has ended");
            int count = Math.min(source.remaining(), bytes.length - size);
            int tail = (head + size) % bytes.length;
            int first = Math.min(count, bytes.length - tail);
            source.get(bytes, tail, first);
            source.get(bytes, 0, count - first);
            size += count;
            if (count > 0) changed.signalAll();
            return count;
        } finally { lock.unlock(); }
    }

    /** Buffered data remains readable before EOF. Input half-close does not close output. */
    void endOfInput() {
        lock.lock();
        try { ended = true; changed.signalAll(); }
        finally { lock.unlock(); }
    }

    /** Abort wins over queued bytes and EOF, and wakes blocked readers. The first failure is retained. */
    void fail(IOException cause) {
        lock.lock();
        try {
            if (failure == null) failure = cause;
            size = 0;
            changed.signalAll();
        } finally { lock.unlock(); }
    }

    /** Applies to reads started after this call, as with a socket read timeout. Zero disables it. */
    void readTimeoutMillis(int timeoutMillis) {
        if (timeoutMillis < 0) throw new IllegalArgumentException("Negative timeout");
        lock.lock();
        try { this.timeoutMillis = timeoutMillis; }
        finally { lock.unlock(); }
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) == -1 ? -1 : one[0] & 0xff;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, target.length);
        if (length == 0) return 0;
        boolean notifyCapacity = false;
        lock.lock();
        try {
            boolean timed = timeoutMillis != 0;
            long remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (size == 0 && !ended && failure == null) {
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
