package io.muserver;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

class Http2ContinuationFrame implements LogicalHttp2Frame {

    private final int streamId;
    private final boolean endHeaders;
    private final byte[] fragment;

    Http2ContinuationFrame(int streamId, boolean endHeaders, byte[] fragment) {
        this.streamId = streamId;
        this.endHeaders = endHeaders;
        this.fragment = fragment;
    }

    public int streamId() {
        return streamId;
    }

    public boolean endHeaders() {
        return endHeaders;
    }

    public byte[] fragment() {
        return fragment;
    }

    static Http2ContinuationFrame readFrom(Http2FrameHeader header, ByteBuffer buffer) throws Http2Exception {
        var endHeaders = (header.flags() & 0b00000100) > 0;
        byte[] fragment = new byte[header.length()];
        buffer.get(fragment);
        return new Http2ContinuationFrame(header.streamId(), endHeaders, fragment);
    }


    @Override
    public void writeTo(Http2Peer connection, OutputStream out) throws IOException {
        var size = fragment.length;

        out.write(new byte[] {
            // payload length
            (byte)(size >> 16),
            (byte)(size >> 8),
            (byte)size,

            // type - 0x9
            0b00001001,

            // flags
            endHeaders ? (byte)0b00000100 : (byte)0,

            // stream id
            (byte) (streamId >> 24),
            (byte) (streamId >> 16),
            (byte) (streamId >> 8),
            (byte) streamId

        });

        out.write(fragment);
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Http2ContinuationFrame that = (Http2ContinuationFrame) o;
        return streamId == that.streamId && endHeaders == that.endHeaders && Objects.deepEquals(fragment, that.fragment);
    }

    @Override
    public int hashCode() {
        return Objects.hash(streamId, endHeaders, Arrays.hashCode(fragment));
    }

    @Override
    public String toString() {
        return "Http2ContinuationFrame{" +
            "streamId=" + streamId +
            ", endHeaders=" + endHeaders +
            ", fragmentLen=" + fragment.length +
            '}';
    }
}
