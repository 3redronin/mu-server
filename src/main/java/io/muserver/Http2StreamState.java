package io.muserver;

/**
 * The RFC 9113 state of a client-initiated HTTP/2 stream from the server's perspective.
 *
 * <p>This is a pure state-transition type. It does not perform I/O or decide which HTTP/2
 * error to report when a caller attempts an invalid transition.</p>
 */
enum Http2StreamState {
    OPEN,
    HALF_CLOSED_LOCAL,
    HALF_CLOSED_REMOTE,
    CLOSED;

    /**
     * @return True if the peer is still permitted to send DATA or trailing HEADERS.
     */
    boolean canReceiveEndStream() {
        return this == OPEN || this == HALF_CLOSED_LOCAL;
    }

    /**
     * @return True if the server is still permitted to send response HEADERS or DATA.
     */
    boolean canSendEndStream() {
        return this == OPEN || this == HALF_CLOSED_REMOTE;
    }

    /**
     * Applies receipt of a frame carrying END_STREAM.
     *
     * @return The resulting stream state.
     * @throws IllegalStateException If the remote side of the stream is already closed.
     */
    Http2StreamState remoteEndStream() {
        switch (this) {
            case OPEN:
                return HALF_CLOSED_REMOTE;
            case HALF_CLOSED_LOCAL:
                return CLOSED;
            default:
                throw new IllegalStateException("Cannot receive END_STREAM in state " + this);
        }
    }

    /**
     * Applies sending a frame carrying END_STREAM.
     *
     * @return The resulting stream state.
     * @throws IllegalStateException If the local side of the stream is already closed.
     */
    Http2StreamState localEndStream() {
        switch (this) {
            case OPEN:
                return HALF_CLOSED_LOCAL;
            case HALF_CLOSED_REMOTE:
                return CLOSED;
            default:
                throw new IllegalStateException("Cannot send END_STREAM in state " + this);
        }
    }

    /**
     * Applies sending or receiving RST_STREAM.
     *
     * @return The closed state.
     */
    Http2StreamState reset() {
        return CLOSED;
    }
}
