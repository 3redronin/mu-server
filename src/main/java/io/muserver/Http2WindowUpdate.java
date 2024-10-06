package io.muserver;

import java.nio.ByteBuffer;
import java.util.Objects;

class Http2WindowUpdate {

    private final int streamId;
    private final int windowSizeIncrement;

    Http2WindowUpdate(int streamId, int windowSizeIncrement) {
        this.streamId = streamId;
        this.windowSizeIncrement = windowSizeIncrement;
    }

    public int streamId() {
        return streamId;
    }

    public int windowSizeIncrement() {
        return windowSizeIncrement;
    }

    public Http2Level level() {
        return streamId == 0 ? Http2Level.CONNECTION : Http2Level.STREAM;
    }

    static Http2WindowUpdate readFrom(Http2FrameHeader header, ByteBuffer buffer) throws Http2Exception {
        int size = buffer.getInt() & 0x7FFFFFFF;
        if (size == 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "window size increment 0", header.streamId());
        }
        return new Http2WindowUpdate(header.streamId(), size);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Http2WindowUpdate that = (Http2WindowUpdate) o;
        return streamId == that.streamId && windowSizeIncrement == that.windowSizeIncrement;
    }

    @Override
    public int hashCode() {
        return Objects.hash(streamId, windowSizeIncrement);
    }

    @Override
    public String toString() {
        return "Http2WindowUpdate{" +
            "streamId=" + streamId +
            ", windowSizeIncrement=" + windowSizeIncrement +
            '}';
    }
}
