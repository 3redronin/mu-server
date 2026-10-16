package io.muserver;

import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

/** Writer-owned encoding storage. Scratch can be recycled only after transport acknowledgement. */
final class Http2FrameBuffer extends OutputStream {
    private final byte[] smallBuffer = new byte[256];
    private byte[] buf = smallBuffer;
    private int count;

    byte[] rawBuffer() { return buf; }
    int size() { return count; }

    @Override public void write(int value) {
        grow(count + 1);
        buf[count++] = (byte) value;
    }

    @Override public void write(byte[] bytes, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, bytes.length);
        grow(count + length);
        System.arraycopy(bytes, offset, buf, count, length);
        count += length;
    }

    private void grow(int required) {
        if (required < 0) throw new OutOfMemoryError("HTTP/2 frame encoding is too large");
        if (required <= buf.length) return;
        byte[] previous = buf;
        if (required <= Http2WriteBatch.SCRATCH_BUFFER_BYTES) {
            buf = Http2WriteBatch.acquireScratch();
            System.arraycopy(previous, 0, buf, 0, count);
        } else {
            buf = Arrays.copyOf(previous, Math.max(required, previous.length * 2));
        }
        // Growth happens before submission; no transport can still borrow the old prefix.
        Http2WriteBatch.releaseScratch(previous);
    }

    void release() {
        Http2WriteBatch.releaseScratch(buf);
        discard();
    }

    // Failure may race an outstanding transport borrow. Drop ownership without recycling;
    // the transport's reference keeps this storage alive until it acknowledges cancellation.
    void discard() {
        buf = smallBuffer;
        count = 0;
    }
}
