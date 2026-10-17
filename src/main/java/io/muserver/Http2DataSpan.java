package io.muserver;

import java.io.OutputStream;
import java.util.Objects;

/** One stable source borrow; the coordinator emits and acknowledges its DATA fragments in order. */
final class Http2DataSpan extends Http2DataFrame {
    Http2DataSpan(int streamId, boolean endStream, byte[] payload, int offset, int length) {
        super(streamId, endStream, payload, offset, length);
        Objects.checkFromIndexSize(offset, length, payload.length);
        if (length > ResponseWriteSizes.MAX_BORROW_BYTES) throw new IllegalArgumentException("DATA borrow exceeds its bound");
    }

    @Override public void writeTo(Http2Peer connection, OutputStream out) {
        throw new IllegalStateException("A DATA span must be fragmented by the coordinator");
    }
}
