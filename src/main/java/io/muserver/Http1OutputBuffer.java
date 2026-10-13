package io.muserver;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Coalesces chunk framing and payload before a transport handoff without growing the response buffer.
 * The lock retains buffer ownership until a blocking write returns without pinning a virtual carrier.
 */
final class Http1OutputBuffer extends OutputStream {
    private final OutputStream output;
    private final byte[] bytes;
    private final ReentrantLock lock = new ReentrantLock();
    private int count;
    private boolean closed;

    Http1OutputBuffer(OutputStream output, int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Positive response buffer capacity required");
        this.output = Objects.requireNonNull(output);
        bytes = new byte[capacity];
    }

    @Override public void write(int value) throws IOException {
        lock.lock();
        try {
            ensureOpen();
            if (count == bytes.length) flushBuffer();
            bytes[count++] = (byte) value;
        } finally { lock.unlock(); }
    }

    @Override public void write(byte[] source, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, source.length);
        lock.lock();
        try {
            ensureOpen();
            if (length == 0) return;
            if (count > 0) {
                int copied = Math.min(length, bytes.length - count);
                System.arraycopy(source, offset, bytes, count, copied);
                count += copied;
                offset += copied;
                length -= copied;
                if (length == 0) return;
                // Fill the framing prefix with payload before handing off the buffer. Flushing
                // just the prefix first would add a separate transport round trip per chunk.
                flushBuffer();
            }
            if (length >= bytes.length) {
                output.write(source, offset, length);
            } else {
                System.arraycopy(source, offset, bytes, 0, length);
                count = length;
            }
        } finally { lock.unlock(); }
    }

    @Override public void flush() throws IOException {
        lock.lock();
        try {
            ensureOpen();
            flushBuffer();
            output.flush();
        } finally { lock.unlock(); }
    }

    /** The connection owns the underlying output; closing a response only flushes it. */
    @Override public void close() throws IOException {
        lock.lock();
        try {
            if (closed) return;
            closed = true;
            flushBuffer();
            output.flush();
        } finally { lock.unlock(); }
    }

    private void flushBuffer() throws IOException {
        if (count == 0) return;
        output.write(bytes, 0, count);
        count = 0;
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Response output buffer is closed");
    }
}
