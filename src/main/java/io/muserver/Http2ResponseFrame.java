package io.muserver;

import java.io.IOException;
import java.io.OutputStream;

/** A known response prefix and DATA fragment sharing one transport write. */
final class Http2ResponseFrame extends Http2DataFrame {
    private final Http2HeadersFrame headers;
    private final boolean includeHeaders;

    Http2ResponseFrame(Http2HeadersFrame headers, boolean endStream, byte[] payload, int offset, int length) {
        this(headers, endStream, payload, offset, length, true);
    }

    private Http2ResponseFrame(Http2HeadersFrame headers, boolean endStream, byte[] payload, int offset, int length, boolean includeHeaders) {
        super(headers.streamId(), endStream, payload, offset, length);
        this.headers = headers;
        this.includeHeaders = includeHeaders;
    }

    Http2HeadersFrame headers() {
        return headers;
    }

    Http2ResponseFrame slice(int offset, int length, boolean last, boolean withHeaders) {
        return new Http2ResponseFrame(headers, last, payload(), offset, length, withHeaders);
    }

    @Override
    public void writeTo(Http2Peer connection, OutputStream out) throws IOException {
        // HPACK remains serialized on the connection writer. This is only used
        // for small known bodies; fragmented header blocks retain their framing.
        var bytes = new NiceByteArrayOutputStream(128 + payloadLength());
        if (includeHeaders) headers.writeTo(connection, bytes);
        if (payloadLength() > 0) {
            new Http2DataFrame(streamId(), false, payload(), payloadOffset(), payloadLength()).writeTo(connection, bytes);
        }
        // Preserve the existing DATA + empty END_STREAM framing while batching
        // their transport write and completion, rather than delaying any flush.
        if (endStream()) Http2DataFrame.eos(streamId()).writeTo(connection, bytes);
        out.write(bytes.rawBuffer(), 0, bytes.size());
    }
}
