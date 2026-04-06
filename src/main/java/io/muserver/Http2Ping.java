package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

class Http2Ping implements LogicalHttp2Frame {
    private static final int ACK_FLAG = 0b00000001;

    private final boolean isAck;
    private final byte[] opaqueData;

    Http2Ping(boolean isAck, byte[] opaqueData) {
        if (opaqueData.length != 8) {
            throw new IllegalArgumentException("PING opaque data must be 8 bytes");
        }
        this.isAck = isAck;
        this.opaqueData = Arrays.copyOf(opaqueData, opaqueData.length);
    }

    public boolean isAck() {
        return isAck;
    }

    public byte[] opaqueData() {
        return Arrays.copyOf(opaqueData, opaqueData.length);
    }

    static Http2Ping readFrom(Http2FrameHeader header, ByteBuffer buffer) throws Http2Exception {
        if (header.length() != 8) {
            throw Http2Exception.connection(Http2ErrorCode.FRAME_SIZE_ERROR, "PING payload must be 8 bytes");
        }
        if (header.streamId() != 0) {
            throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "PING must be on stream 0");
        }
        byte[] opaqueData = new byte[8];
        buffer.get(opaqueData);
        return new Http2Ping((header.flags() & ACK_FLAG) == ACK_FLAG, opaqueData);
    }

    @Override
    public void writeTo(Http2Peer connection, OutputStream out) throws IOException {
        out.write(new byte[] {
            0, 0, 8,
            0x06,
            (byte) (isAck ? ACK_FLAG : 0),
            0, 0, 0, 0,
            opaqueData[0], opaqueData[1], opaqueData[2], opaqueData[3],
            opaqueData[4], opaqueData[5], opaqueData[6], opaqueData[7]
        });
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Http2Ping http2Ping = (Http2Ping) o;
        return isAck == http2Ping.isAck && Objects.deepEquals(opaqueData, http2Ping.opaqueData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(isAck, Arrays.hashCode(opaqueData));
    }

    @Override
    public String toString() {
        return "Http2Ping{" +
            "isAck=" + isAck +
            ", opaqueData=" + Arrays.toString(opaqueData) +
            '}';
    }
}
