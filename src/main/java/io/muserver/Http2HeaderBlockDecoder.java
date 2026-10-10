package io.muserver;

import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;

/** Assembles one HEADERS/CONTINUATION field block without reading from a transport. */
final class Http2HeaderBlockDecoder {
    private static final int MAX_EMPTY_CONTINUATIONS = 128;
    private final Http2FrameHeader firstHeader;
    private final FieldBlockDecoder hpack;
    private final int maximumBufferedBytes;
    private @Nullable Http2FrameHeader pendingHeader;
    private @Nullable ByteBuffer fragments;
    private @Nullable Http2Exception deferredStreamError;
    private @Nullable Exception failure;
    private boolean first = true;
    private boolean complete;
    private int emptyContinuations;

    Http2HeaderBlockDecoder(Http2FrameHeader firstHeader, FieldBlockDecoder hpack, int maximumBufferedBytes) {
        if (maximumBufferedBytes < 1) throw new IllegalArgumentException("Positive field-block limit required");
        this.firstHeader = firstHeader;
        this.hpack = hpack;
        this.maximumBufferedBytes = maximumBufferedBytes;
        pendingHeader = firstHeader;
    }

    /** Validate ordering and storage bounds before waiting for the continuation payload. */
    void continuation(Http2FrameHeader header) throws Http2Exception {
        checkFailure();
        if (complete || first || pendingHeader != null) throw new IllegalStateException("No continuation header is expected");
        try {
            if (header.frameType() != Http2FrameType.CONTINUATION) {
                throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "invalid frame type: expected CONTINUATION");
            }
            if (header.streamId() != firstHeader.streamId()) {
                throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "stream id mismatch");
            }
            if (header.length() == 0 && ++emptyContinuations > MAX_EMPTY_CONTINUATIONS) {
                throw Http2Exception.connection(Http2ErrorCode.COMPRESSION_ERROR, "Too many empty CONTINUATION frames in one field block");
            }
            requireCapacity(header.length());
            pendingHeader = header;
        } catch (Http2Exception error) { failure = error; throw error; }
    }

    /**
     * Consume exactly one complete physical frame payload, leaving following bytes untouched.
     * No supplied storage is retained. Null means another CONTINUATION is required.
     */
    @Nullable Http2HeadersFrame payload(ByteBuffer source) throws Http2Exception {
        checkFailure();
        if (complete) throw new IllegalStateException("The field block has already ended");
        Http2FrameHeader header = java.util.Objects.requireNonNull(pendingHeader, "A frame header is required");
        if (source.remaining() < header.length()) throw new IllegalArgumentException("A complete frame payload is required");
        int end = source.position() + header.length();
        pendingHeader = null;
        try {
            int length = header.length();
            if (first) {
                int padding = 0;
                if ((header.flags() & 8) != 0) {
                    if (length < 1) throw Http2Exception.connection(Http2ErrorCode.FRAME_SIZE_ERROR, "HEADERS frame missing pad length");
                    padding = source.get() & 255;
                    length--;
                    if (padding > length) throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "padding is longer than remaining payload");
                    length -= padding;
                }
                if ((header.flags() & 32) != 0) {
                    if (length < 5) throw Http2Exception.connection(Http2ErrorCode.FRAME_SIZE_ERROR, "HEADERS priority fields require 5 bytes");
                    length -= 5;
                    int dependency = source.getInt() & 0x7fffffff;
                    source.get();
                    if (dependency == header.streamId()) {
                        deferredStreamError = Http2Exception.stream(Http2ErrorCode.PROTOCOL_ERROR,
                            "HEADERS stream cannot depend on itself", header.streamId());
                    }
                }
                first = false;
            }
            ByteBuffer fragment = source.slice().limit(length);
            boolean last = (header.flags() & 4) != 0;
            if (fragments == null && last) {
                complete = true;
                return decode(fragment, length == 0);
            }
            append(fragment);
            if (!last) return null;
            complete = true;
            ByteBuffer block = java.util.Objects.requireNonNull(fragments).flip();
            fragments = null;
            return decode(block, false);
        } catch (Http2Exception | HttpException error) {
            failure = error;
            fragments = null;
            throw error;
        } finally { source.position(end); }
    }

    private Http2HeadersFrame decode(ByteBuffer block, boolean emptyInitialBlock) throws Http2Exception {
        FieldBlock headers;
        HttpException rejected = null;
        try { headers = emptyInitialBlock ? new FieldBlock() : hpack.decodeFrom(block); }
        catch (HttpException error) { rejected = error; headers = new FieldBlock(); }
        // Decode all HPACK instructions before a recoverable error, preserving the shared table.
        // Compression errors above still take precedence over both recoverable error categories.
        if (deferredStreamError != null) throw deferredStreamError;
        if (rejected != null) throw rejected;
        return new Http2HeadersFrame(firstHeader.streamId(), (firstHeader.flags() & 1) != 0, headers);
    }

    private void append(ByteBuffer fragment) throws Http2Exception {
        requireCapacity(fragment.remaining());
        ByteBuffer accumulated = fragments;
        if (accumulated == null) {
            accumulated = ByteBuffer.allocate(Math.min(maximumBufferedBytes, Math.max(32, fragment.remaining())));
            fragments = accumulated;
        } else if (fragment.remaining() > accumulated.remaining()) {
            int required = accumulated.position() + fragment.remaining();
            int capacity = (int) Math.min(maximumBufferedBytes, Math.max((long) required, (long) accumulated.capacity() * 2));
            ByteBuffer larger = ByteBuffer.allocate(capacity);
            larger.put(accumulated.flip());
            fragments = accumulated = larger;
        }
        accumulated.put(fragment);
    }

    private void requireCapacity(int extra) throws Http2Exception {
        int current = fragments == null ? 0 : fragments.position();
        if (extra > maximumBufferedBytes - current) {
            throw Http2Exception.connection(Http2ErrorCode.COMPRESSION_ERROR, "Encoded field block exceeds the configured buffering limit");
        }
    }

    private void checkFailure() throws Http2Exception {
        if (failure instanceof Http2Exception) throw (Http2Exception) failure;
        if (failure instanceof HttpException) throw (HttpException) failure;
    }
}
