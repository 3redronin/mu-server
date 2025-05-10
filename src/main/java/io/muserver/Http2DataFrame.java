package io.muserver;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

class Http2DataFrame implements LogicalHttp2Frame {

    private static final byte eosFlag = (byte) 0b00000001;
    private static final byte paddedFlag = (byte) 0b00001000;
    private static final byte notEosFlag = (byte) 0b00000000;
    private final int streamId;
    private final boolean eos;
    private final byte[] payload;
    private final int payloadOffset;
    private final int payloadLength;

    Http2DataFrame(int streamId, boolean eos, byte[] payload, int payloadOffset, int payloadLength) {
        this.streamId = streamId;
        this.eos = eos;
        this.payload = payload;
        this.payloadOffset = payloadOffset;
        this.payloadLength = payloadLength;
    }

    public static Http2DataFrame readFrom(Http2FrameHeader header, ByteBuffer buffer) throws Http2Exception {

        boolean eos = (header.flags() & eosFlag) == eosFlag;
        boolean padded = (header.flags() & paddedFlag) == paddedFlag;

        int padLength;
        int dataLength;
        if (padded) {
            padLength = buffer.get() & 0xFF;
            if (padLength >= header.length()) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Padding length too large");
            }
            dataLength = header.length() - 1 - padLength;
        } else {
            padLength = 0;
            dataLength = header.length();
        }

        byte[] data = new byte[dataLength];

        buffer.get(data);

        if (padded) {
            buffer.position(buffer.position() + padLength);
        }

        return new Http2DataFrame(header.streamId(), eos, data, 0, dataLength);
    }

    @Override
    public int flowControlSize() {
        return payloadLength;
    }

    @Override
    public boolean endStream() {
        return eos;
    }

    public int streamId() {
        return streamId;
    }

    public byte[] payload() {
        return payload;
    }

    public int payloadOffset() {
        return payloadOffset;
    }

    public int payloadLength() {
        return payloadLength;
    }

    @Override
    public void writeTo(Http2Peer connection, OutputStream out) throws IOException {
        out.write(new byte[] {
            // len
            (byte)(payloadLength >> 16),
            (byte)(payloadLength >> 8),
            (byte)payloadLength,
            // type
            (byte)0,
            // flags
            eos ? eosFlag : notEosFlag,
            // stream id
            (byte)(streamId >> 24),
            (byte)(streamId >> 16),
            (byte)(streamId >> 8),
            (byte)(streamId)
        });
        out.write(payload, payloadOffset, payloadLength);
    }

    @Override
    public String toString() {
        return "Http2DataFrame{" +
            "streamId=" + streamId +
            ", eos=" + eos +
            ", payloadLength=" + payloadLength +
            '}';
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        Http2DataFrame that = (Http2DataFrame) o;
        return streamId == that.streamId && eos == that.eos && payloadOffset == that.payloadOffset && payloadLength == that.payloadLength && Arrays.equals(payload, payloadOffset, payloadLength, that.payload, that.payloadOffset, that.payloadLength);
    }

    @Override
    public int hashCode() {
        int result = streamId;
        result = 31 * result + Boolean.hashCode(eos);
        result = 31 * result + Arrays.hashCode(payload);
        result = 31 * result + payloadOffset;
        result = 31 * result + payloadLength;
        return result;
    }
}
