package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

class Http2WindowUpdate implements LogicalHttp2Frame {

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
    public void writeTo(Http2Peer connection, OutputStream out) throws IOException {
        out.write(new byte[] {
            // payload length
            0b00000000,
            0b00000000,
            0b00000100,

            // type
            0x08,

            // unused flags
            0b00000000,

            // stream id
            (byte) (streamId >> 24),
            (byte) (streamId >> 16),
            (byte) (streamId >> 8),
            (byte) streamId,

            // error code
            (byte) (windowSizeIncrement >> 24),
            (byte) (windowSizeIncrement >> 16),
            (byte) (windowSizeIncrement >> 8),
            (byte) windowSizeIncrement
        });
    }

    @Override
    public boolean equals(@Nullable Object o) {
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
