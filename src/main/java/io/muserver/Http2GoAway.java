package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

class Http2GoAway implements LogicalHttp2Frame {

    private final int lastStreamId;
    private final int errorCode;
    private final byte @Nullable [] debugData;

    public Http2GoAway(int lastStreamId, int errorCode, byte @Nullable [] debugData) {
        this.lastStreamId = lastStreamId;
        this.errorCode = errorCode;
        this.debugData = debugData;
    }

    public int lastStreamId() {
        return lastStreamId;
    }

    public int errorCode() {
        return errorCode;
    }

    public byte @Nullable [] debugData() {
        return debugData;
    }

    @Nullable
    public Http2ErrorCode errorCodeEnum() {
        return Http2ErrorCode.fromCode(errorCode);
    }

    public String debugDataAsUTF8String() {
        var data = debugData;
        if (data == null) return "";
        return new String(data, StandardCharsets.UTF_8);
    }

    static Http2GoAway readFrom(Http2FrameHeader header, ByteBuffer buffer) throws Http2Exception {
        int lastStreamId = buffer.getInt() & 0x7FFFFFFF;
        int errorCode = buffer.getInt() & 0x7FFFFFFF;
        byte[] debugData = new byte[header.length() - 8];
        buffer.get(debugData);
        return new Http2GoAway(lastStreamId, errorCode, debugData);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Http2GoAway that = (Http2GoAway) o;
        return lastStreamId == that.lastStreamId && errorCode == that.errorCode && Objects.deepEquals(debugData, that.debugData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lastStreamId, errorCode, Arrays.hashCode(debugData));
    }

    @Override
    public String toString() {
        var c = this.errorCodeEnum();
        return "Http2GoAway{" +
            "lastStreamId=" + lastStreamId +
            ", errorCode=" + (c == null ? errorCode : c) +
            ", debugData=" + Arrays.toString(debugData) +
            '}';
    }

    @Override
    public void writeTo(Http2Connection connection, OutputStream out) throws IOException {
        if (debugData != null) {
            throw new IllegalStateException("Debug data not supported");
        }
        // without debug data, the payload is 2 ints - so 8 bytes
        out.write(new byte[] {
            // payload length
            0b00000000,
            0b00000000,
            0b00001000,

            // type - 0x7
            0b00000111,

            // unused flags
            0b00000000,

            // stream id - always 0
            0b00000000,
            0b00000000,
            0b00000000,
            0b00000000,

            // last stream id
            (byte) (lastStreamId >> 24),
            (byte) (lastStreamId >> 16),
            (byte) (lastStreamId >> 8),
            (byte) lastStreamId,

            // error code
            (byte) (errorCode >> 24),
            (byte) (errorCode >> 16),
            (byte) (errorCode >> 8),
            (byte) errorCode
        });
    }
}
