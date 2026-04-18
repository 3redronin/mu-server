package io.muserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * A logical header frame - so in data it may be represented as multiple frames (i.e. with continuations)
 */
class Http2HeadersFrame implements LogicalHttp2Frame {

    private final int streamId;
    private final boolean endStream;
    private final FieldBlock headers;

    Http2HeadersFrame(int streamId, boolean endStream, FieldBlock headers) {
        this.streamId = streamId;
        this.endStream = endStream;
        this.headers = headers;
    }

    /**
     * Reads a logical headers frame, combining multiple fragments into one if needed.
     *
     * @param frameHeader the HTTP2 frame header ("header" refers to the first 9 bytes all H2 frames have in common rather than HTTP headers)
     * @param fieldBlockDecoder the decoder
     * @param buffer the buffer with the current frame at least loaded
     * @param clientIn the input to read from when continuation frames are used - used to get fragments to build a full header
     * @return parsed HTTP2 headers
     * @throws HttpException if the request is invalid due to things like headers or URI being too long
     * @throws Http2Exception if there is an H2/HPACK protocol exception
     * @throws IOException if there is an error reading from the client input during processing
     */
    static Http2HeadersFrame readLogicalFrame(Http2FrameHeader frameHeader, FieldBlockDecoder fieldBlockDecoder, ByteBuffer buffer, InputStream clientIn) throws HttpException, Http2Exception, IOException {
        // figure out the fields
        var priority = (frameHeader.flags() & 0b00100000) > 0;
        var padded = (frameHeader.flags() & 0b00001000) > 0;
        var endHeaders = (frameHeader.flags() & 0b00000100) > 0;
        var endStream = (frameHeader.flags() & 0b0000001) > 0;
        int hpackLength = frameHeader.length();

        int padLength = 0;
        if (padded) {
            if (hpackLength < 1) {
                throw Http2Exception.connection(Http2ErrorCode.FRAME_SIZE_ERROR, "HEADERS frame missing pad length");
            }
            padLength = buffer.get() & 0xff;
            hpackLength -= 1;
            if (padLength > hpackLength) {
                throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "padding is longer than remaining payload");
            }
            hpackLength -= padLength;
        }

        if (priority) {
            if (hpackLength < 5) {
                throw Http2Exception.connection(Http2ErrorCode.FRAME_SIZE_ERROR, "HEADERS priority fields require 5 bytes");
            }
            hpackLength -= 5;
            int streamDependency = buffer.getInt() & 0x7FFFFFFF;
            buffer.get();
            if (streamDependency == frameHeader.streamId()) {
                throw Http2Exception.stream(Http2ErrorCode.PROTOCOL_ERROR, "HEADERS stream cannot depend on itself", frameHeader.streamId());
            }
        }

        // add name/value strings as:
        FieldBlock headers;

        NiceByteArrayOutputStream baos = null;
        HttpException invalidRequestException = null;

        if (endHeaders) {
            if (hpackLength > 0) {
                var slice = buffer.slice().limit(hpackLength);
                try {
                    headers = fieldBlockDecoder.decodeFrom(slice);
                } catch (HttpException e) {
                    // we need to process everything and get the buffer in the right place, which is why it is not thrown
                    invalidRequestException = e;
                    headers = new FieldBlock();
                }
            } else {
                headers = new FieldBlock();
            }
        } else {
            baos = new NiceByteArrayOutputStream(Math.max(32, hpackLength * 2));
            if (hpackLength > 0) {
                if (buffer.hasArray()) {
                    baos.write(buffer.array(), buffer.arrayOffset() + buffer.position(), hpackLength);
                } else {
                    // TODO: support non-array buffer
                    throw new IllegalStateException("Not supported");
                }
            }
            headers = null;
        }
        buffer.position(buffer.position() + hpackLength);

        if (padLength > 0) {
            buffer.position(buffer.position() + padLength);
        }

        if (baos != null) {
            var ended = false;
            while (!ended) {
                Mutils.readAtLeast(buffer, clientIn, Http2FrameHeader.FRAME_HEADER_LENGTH);
                var hf = Http2FrameHeader.readFrom(buffer);
                if (hf.frameType() != Http2FrameType.CONTINUATION) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "invalid frame type: expected CONTINUATION");
                }
                Mutils.readAtLeast(buffer, clientIn, hf.length());
                var cf = Http2ContinuationFrame.readFrom(hf, buffer);
                if (cf.streamId() != frameHeader.streamId()) {
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "stream id mismatch");
                }
                baos.write(cf.fragment());
                ended = cf.endHeaders();
            }
            try {
                headers = fieldBlockDecoder.decodeFrom(baos.toByteBuffer());
            } catch (HttpException e) {
                invalidRequestException = e;
                headers = new FieldBlock();
            }
        }

        if (invalidRequestException != null) {
            throw invalidRequestException;
        }

        return new Http2HeadersFrame(frameHeader.streamId(), endStream, headers);
    }

    @Override
    public boolean endStream() {
        return endStream;
    }

    public FieldBlock headers() {
        return headers;
    }

    @Override
    public void writeTo(Http2Peer connection, OutputStream out) throws IOException {

        var baos = new NiceByteArrayOutputStream(32);
        baos.write(new byte[] { 0, 0, 0,
            /* type */ 0x01,
            /* flags */ endStream ? (byte)0b00000101 : (byte)0b00000100,
            (byte)(streamId >> 24),
            (byte)(streamId >> 16),
            (byte)(streamId >> 8),
            (byte)(streamId),
        });

        int size = baos.size();

        connection.fieldBlockEncoder().encodeTo(headers, baos);

        size = baos.size() - size;

        int remaining = size;
        int maxFrameSize = connection.maxFrameSize();
        int messages = Math.max(1, (size + maxFrameSize - 1) / maxFrameSize);

        for (int i = 0; i < messages; i++) {
            int payloadBytes = Math.min(remaining, maxFrameSize);
            if (i == 0) {
                // header fragment

                byte[] toWrite = baos.rawBuffer();
                toWrite[0] = (byte)(payloadBytes >> 16);
                toWrite[1] = (byte)(payloadBytes >> 8);
                toWrite[2] = (byte)payloadBytes;

                if (messages > 1) {
                    // set endheaders to false
                    toWrite[4] = (byte) (toWrite[4] & 0b11111111111111111111111111111011);
                }

                out.write(toWrite, 0, 9 + payloadBytes);
            } else {
                // continuation
                out.write(new byte[] {
                    // size
                    (byte)(payloadBytes >> 16),
                    (byte)(payloadBytes >> 8),
                    (byte)payloadBytes,
                    //type
                    0x9,
                    // flags
                    i == messages - 1 ? (byte)0b00000100 : (byte)0,
                    // stream
                    (byte)(streamId >> 24),
                    (byte)(streamId >> 16),
                    (byte)(streamId >> 8),
                    (byte)(streamId),
                });
                out.write(baos.rawBuffer(), 9 + i * maxFrameSize, payloadBytes);
            }
            remaining -= payloadBytes;
        }


    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Http2HeadersFrame that = (Http2HeadersFrame) o;
        return endStream == that.endStream && Objects.equals(headers, that.headers);
    }

    @Override
    public int hashCode() {
        return Objects.hash(endStream, headers);
    }

    @Override
    public String toString() {
        return "Http2HeaderFragment{" +
            "endStream=" + endStream +
            ", headers=" + headers +
            '}';
    }

    public int streamId() {
        return streamId;
    }
}
