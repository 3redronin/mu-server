package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

class Http2ResetStreamFrame implements LogicalHttp2Frame {
    private final int streamId;
    private final int errorCode;

    public Http2ResetStreamFrame(int streamId, int errorCode) {
        this.streamId = streamId;
        this.errorCode = errorCode;
    }

    public int streamId() {
        return streamId;
    }

    public int errorCode() {
        return errorCode;
    }

    @Nullable
    public Http2ErrorCode errorCodeEnum() {
        return Http2ErrorCode.fromCode(errorCode);
    }

    @Override
    public void writeTo(Http2Peer connection, OutputStream out) throws IOException {
        out.write(new byte[] {
            // payload length
            0b00000000,
            0b00000000,
            0b00000100,

            // type
            0x3,

            // unused flags
            0b00000000,

            // stream id
            (byte) (streamId >> 24),
            (byte) (streamId >> 16),
            (byte) (streamId >> 8),
            (byte) streamId,

            // error code
            (byte) (errorCode >> 24),
            (byte) (errorCode >> 16),
            (byte) (errorCode >> 8),
            (byte) errorCode
        });
    }


    static Http2ResetStreamFrame readFrom(Http2FrameHeader header, ByteBuffer buffer) throws Http2Exception {
        int errorCode = buffer.getInt() & 0x7FFFFFFF;
        return new Http2ResetStreamFrame(header.streamId(), errorCode);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        Http2ResetStreamFrame that = (Http2ResetStreamFrame) o;
        return streamId == that.streamId && errorCode == that.errorCode;
    }

    @Override
    public int hashCode() {
        int result = streamId;
        result = 31 * result + errorCode;
        return result;
    }

    @Override
    public String toString() {
        return "Http2ResetStreamFrame{" +
            "streamId=" + streamId +
            ", errorCode=" + errorCode +
            '}';
    }
}
