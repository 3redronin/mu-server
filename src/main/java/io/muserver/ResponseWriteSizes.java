package io.muserver;

/** Bounded response turns, leaving framing headroom when it does not add a final turn. */
final class ResponseWriteSizes {
    static final int MAX_SOURCE_BYTES = 16_384;
    // Room for an HTTP/2 header and TLS 1.3 inner content type/padding.
    static final int PREFERRED_SOURCE_BYTES = MAX_SOURCE_BYTES - 32;
    static final int MAX_BORROW_BYTES = 4 * PREFERRED_SOURCE_BYTES;

    private ResponseWriteSizes() { }

    static int nextFragmentBytes(int remaining) {
        if (remaining <= MAX_SOURCE_BYTES) return remaining;
        if (remaining <= 2 * MAX_SOURCE_BYTES && remaining > PREFERRED_SOURCE_BYTES + MAX_SOURCE_BYTES) {
            return MAX_SOURCE_BYTES;
        }
        return PREFERRED_SOURCE_BYTES;
    }
}
