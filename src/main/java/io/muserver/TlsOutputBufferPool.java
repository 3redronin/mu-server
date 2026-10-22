package io.muserver;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

/**
 * One lazily allocated ciphertext batch per transport owner, shared by all its connections.
 * A blocked connection retains the only lease; other connections use their own record buffers.
 * Access belongs to the owner, or its retirement cleanup after that owner has stopped driving IO.
 */
final class TlsOutputBufferPool {
    static final int PLAINTEXT_BYTES = 128 * 1024;
    static final int CAPACITY = PLAINTEXT_BYTES + 2048;
    private @Nullable ByteBuffer buffer;
    private boolean leased;

    @Nullable ByteBuffer acquire() {
        if (leased) return null;
        if (buffer == null) buffer = ByteBuffer.allocate(CAPACITY);
        leased = true;
        return buffer.clear().flip();
    }

    @SuppressWarnings("ReferenceEquality") // Only the exact leased storage can be returned.
    void release(ByteBuffer released) {
        if (!leased || released != buffer) throw new IllegalStateException("Invalid TLS output lease");
        leased = false;
    }
}
