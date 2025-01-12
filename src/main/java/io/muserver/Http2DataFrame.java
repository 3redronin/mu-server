package io.muserver;

import java.io.IOException;
import java.io.OutputStream;

class Http2DataFrame implements LogicalHttp2Frame {

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


    @Override
    public void writeTo(Http2Connection connection, OutputStream out) throws IOException {
        out.write(new byte[] {
            // len
            (byte)(payloadLength >> 16),
            (byte)(payloadLength >> 8),
            (byte)payloadLength,
            // type
            (byte)0,
            // flags
            eos ? (byte)0b00000001 : (byte)0b00000000,
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
}
