package io.muserver;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

class Http2GoAway {

    private final int lastStreamId;
    private final int errorCode;
    private final byte[] debugData;

    public Http2GoAway(int lastStreamId, int errorCode, byte[] debugData) {
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

    public byte[] debugData() {
        return debugData;
    }

    public Http2ErrorCode errorCodeEnum() {
        return Http2ErrorCode.fromCode(errorCode);
    }

    public String debugDataAsUTF8String() {
        return new String(debugData, StandardCharsets.UTF_8);
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
}
