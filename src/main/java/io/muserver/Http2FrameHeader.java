package io.muserver;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;

class Http2FrameHeader {

    private final int length;
    private final Http2FrameType frameType;
    private final int flags;
    private final int streamId;

    Http2FrameHeader(int length, Http2FrameType frameType, int flags, int streamId) {
        this.length = length;
        this.frameType = frameType;
        this.flags = flags;
        this.streamId = streamId;
    }

    public int length() {
        return length;
    }

    @NotNull
    public Http2FrameType frameType() {
        return frameType;
    }

    public int flags() {
        return flags;
    }

    public int streamId() {
        return streamId;
    }

    static final int FRAME_HEADER_LENGTH = 9;
    @NotNull
    static Http2FrameHeader readFrom(@NotNull ByteBuffer buffer) throws Http2Exception {
        int length = ((buffer.get() & 0xFF) << 16) | ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);
        var frameType = Http2FrameType.fromByte(buffer.get());
        int flags = buffer.get() & 0xFF;
        int streamId = buffer.getInt() & 0x7FFFFFFF;
        boolean hasStream = streamId != 0;
        if (length > buffer.capacity()) {
            var errorType = !hasStream || frameType.hasFieldBlock() || frameType == Http2FrameType.UNKNOWN ? Http2Level.CONNECTION : Http2Level.STREAM;
            if (errorType == Http2Level.CONNECTION) {
                throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, "frame content too large");
            } else {
                throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, "frame content too large", streamId);
            }
        }
        var fixedSize = frameType.fixedSize();
        if (fixedSize >= 0 && fixedSize != length) {
            throw new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, "frame content incorrect for fixed size frame");
        }
        var streamRequired = frameType.hasStream();
        if (streamRequired != null) {
            if ((streamRequired && !hasStream) || (!streamRequired && hasStream)) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Stream ID " + (hasStream ? "" : "not ") + "present for " + frameType);
            }
        }
        return new Http2FrameHeader(length, frameType, flags, streamId);
    }

    @Override
    public String toString() {
        return "Http2FrameHeader{" +
            "length=" + length +
            ", frameType=" + frameType +
            ", flags=" + flags +
            ", streamId=" + streamId +
            '}';
    }
}
