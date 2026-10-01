package io.muserver;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/** Response buffering whose blocking writes do not pin Java 21 virtual threads. */
final class CloseGuardedBufferedOutputStream extends OutputStream {
    private final OutputStream out;
    private final byte[] buffer;
    private final ReentrantLock lock = new ReentrantLock();
    private int count;
    private boolean closed;

    CloseGuardedBufferedOutputStream(OutputStream out, int size) {
        if (size <= 0) throw new IllegalArgumentException("Buffer size must be positive");
        this.out = out;
        this.buffer = new byte[size];
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Response output stream is closed");
    }

    private void flushBuffer() throws IOException {
        if (count > 0) {
            out.write(buffer, 0, count);
            count = 0;
        }
    }

    @Override public void write(int value) throws IOException {
        lock.lock();
        try {
            ensureOpen();
            if (count == buffer.length) flushBuffer();
            buffer[count++] = (byte) value;
        } finally {
            lock.unlock();
        }
    }

    @Override public void write(byte[] bytes) throws IOException {
        write(bytes, 0, bytes.length);
    }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        lock.lock();
        try {
            ensureOpen();
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length >= buffer.length) {
                flushBuffer();
                out.write(bytes, offset, length);
            } else {
                if (length > buffer.length - count) flushBuffer();
                System.arraycopy(bytes, offset, buffer, count, length);
                count += length;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override public void flush() throws IOException {
        lock.lock();
        try {
            ensureOpen();
            flushBuffer();
            out.flush();
        } finally {
            lock.unlock();
        }
    }

    @Override public void close() throws IOException {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            Throwable failure = null;
            try {
                flushBuffer();
                out.flush();
            } catch (Throwable flushFailure) {
                failure = flushFailure;
                throw flushFailure;
            } finally {
                try {
                    out.close();
                } catch (Throwable closeFailure) {
                    if (failure == null) throw closeFailure;
                    @SuppressWarnings("ReferenceEquality") // Throwable forbids self-suppression.
                    boolean different = failure != closeFailure;
                    if (different) failure.addSuppressed(closeFailure);
                }
            }
        } finally {
            lock.unlock();
        }
    }
}
