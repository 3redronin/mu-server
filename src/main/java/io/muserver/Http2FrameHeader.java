package io.muserver;

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

    static Http2FrameHeader readFrom(ByteBuffer buffer) throws Http2Exception {
        int length = ((buffer.get() & 0xFF) << 16) | ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);
        var frameType = Http2FrameType.fromByte(buffer.get());
        int flags = buffer.get() & 0xFF;
        int streamId = buffer.getInt() & 0x7FFFFFFF;
        boolean hasStream = streamId != 0;
        if (length > buffer.capacity()) {
            throw Http2Exception.connection(Http2ErrorCode.FRAME_SIZE_ERROR, "frame content too large");
        }
        var fixedSize = frameType.fixedSize();
        if (frameType != Http2FrameType.PRIORITY && fixedSize >= 0 && fixedSize != length) {
            throw Http2Exception.connection(Http2ErrorCode.FRAME_SIZE_ERROR, "frame content incorrect for fixed size frame");
        }
        var streamRequired = frameType.hasStream();
        if (streamRequired != null) {
            if ((streamRequired && !hasStream) || (!streamRequired && hasStream)) {
                throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "Stream ID " + (hasStream ? "" : "not ") + "present for " + frameType);
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
