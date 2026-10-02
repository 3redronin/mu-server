package io.muserver;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;

/** A bounded buffer owned exclusively by the connection writer. */
final class Http2WriteBatch extends OutputStream {
    // Leave room for the default 8 KiB response chunk and its frame header.
    static final int MAX_BYTES = 16_384;
    private static final int DIRECT_WRITE_MIN_BYTES = 8_192;
    // Cache a bounded number of idle leases instead of a large array per connection.
    private static final ArrayBlockingQueue<byte[]> SCRATCH_BUFFERS = new ArrayBlockingQueue<>(64);
    private final OutputStream out;
    private final Runnable afterWrite;
    @FunctionalInterface
    interface FlushListener {
        void flushed() throws IOException;
    }

    private final FlushListener afterFlush;
    private final byte[] smallBuffer = new byte[256];
    private byte[] buffer = smallBuffer;
    private int count;
    private long acceptedBytes;
    private long writtenBytes;
    private long flushedBytes;

    Http2WriteBatch(OutputStream out, Runnable afterWrite, FlushListener afterFlush) {
        this.out = out;
        this.afterWrite = afterWrite;
        this.afterFlush = afterFlush;
    }

    long acceptedBytes() { return acceptedBytes; }
    long writtenBytes() { return writtenBytes; }
    long flushedBytes() { return flushedBytes; }

    @Override public void write(int value) throws IOException {
        if (count == MAX_BYTES) flush();
        grow(count + 1);
        buffer[count++] = (byte) value;
        acceptedBytes++;
    }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        // An already-consolidated large write needs no retained scratch buffer.
        // Keep buffering when there are preceding bytes that can share its write.
        if (length >= MAX_BYTES || (count == 0 && length >= DIRECT_WRITE_MIN_BYTES)) {
            flush();
            out.write(bytes, offset, length);
            acceptedBytes += length;
            writtenBytes += length;
            afterWrite.run();
        } else {
            if (length > MAX_BYTES - count) flush();
            grow(count + length);
            System.arraycopy(bytes, offset, buffer, count, length);
            count += length;
            acceptedBytes += length;
        }
    }

    private void grow(int required) {
        if (required > buffer.length) {
            byte[] scratch = SCRATCH_BUFFERS.poll();
            if (scratch == null) scratch = new byte[MAX_BYTES];
            System.arraycopy(buffer, 0, scratch, 0, count);
            buffer = scratch;
        }
    }

    /** Return scratch only after transport output has relinquished its ownership. */
    void releaseScratch() {
        if (count != 0) throw new IllegalStateException("Cannot release buffered output");
        if (buffer.length == MAX_BYTES) {
            byte[] scratch = buffer;
            buffer = smallBuffer;
            SCRATCH_BUFFERS.offer(scratch);
        }
    }

    @Override public void flush() throws IOException {
        if (count > 0) {
            out.write(buffer, 0, count);
            writtenBytes += count;
            count = 0;
            afterWrite.run();
        }
        out.flush();
        flushedBytes = writtenBytes;
        afterFlush.flushed();
    }

    void discard() { count = 0; }
}
