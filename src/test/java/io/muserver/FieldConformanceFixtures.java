package io.muserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Raw fixtures deliberately do not use application header factories. */
final class FieldConformanceFixtures {
    static final String TOKEN = "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private FieldConformanceFixtures() { }

    static byte[] octets(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 255) throw new IllegalArgumentException("Not an octet string");
        }
        return value.getBytes(StandardCharsets.ISO_8859_1);
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    static byte[] literal(String name, String value, boolean indexed, boolean huffman) throws IOException {
        return literal(octets(name), octets(value), indexed, huffman);
    }

    static byte[] literal(byte[] name, byte[] value, boolean indexed, boolean huffman) throws IOException {
        var out = new ByteArrayOutputStream();
        out.write(indexed ? 0x40 : 0);
        string(out, name, huffman);
        string(out, value, huffman);
        return out.toByteArray();
    }

    static byte[] named(int index, String value, boolean indexed, boolean huffman) throws IOException {
        var out = new ByteArrayOutputStream();
        FieldBlockEncoder.writeHpackInt(indexed ? 6 : 4, (byte) (indexed ? 0x40 : 0), out, index);
        string(out, octets(value), huffman);
        return out.toByteArray();
    }

    static byte[] indexed(int index) throws IOException {
        var out = new ByteArrayOutputStream();
        FieldBlockEncoder.writeHpackInt(7, (byte) 0x80, out, index);
        return out.toByteArray();
    }

    private static void string(ByteArrayOutputStream out, byte[] bytes, boolean huffman) throws IOException {
        if (huffman) {
            var encoded = new ByteArrayOutputStream();
            HuffmanEncoder.encodeTo(encoded, new String(bytes, StandardCharsets.ISO_8859_1));
            bytes = encoded.toByteArray();
        }
        FieldBlockEncoder.writeHpackInt(7, (byte) (huffman ? 0x80 : 0), out, bytes.length);
        out.writeBytes(bytes);
    }

    static void sendBlock(H2ClientConnection con, int stream, boolean endStream, byte[] block,
                          boolean fragmented) throws IOException {
        if (!fragmented) {
            con.writeRaw(RFCTestUtils.headersFrame(stream, endStream, true, block));
        } else {
            con.writeRaw(RFCTestUtils.headersFrame(stream, endStream, false, Arrays.copyOf(block, 1)));
            for (int i = 1; i < block.length; i++) {
                con.writeRaw(RFCTestUtils.continuationFrame(stream, false, new byte[]{block[i]}));
            }
            con.writeRaw(RFCTestUtils.continuationFrame(stream, true, new byte[0]));
        }
        con.flush();
    }

    static List<LogicalHttp2Frame> untilReset(H2ClientConnection con, int stream) throws Exception {
        var frames = new ArrayList<LogicalHttp2Frame>();
        boolean badRequestResponse = false;
        for (int i = 0; i < 64; i++) {
            var frame = con.readLogicalFrame();
            frames.add(frame);
            if (frame instanceof Http2HeadersFrame && ((Http2HeadersFrame) frame).streamId() == stream) {
                badRequestResponse = "400".equals(((Http2HeadersFrame) frame).headers().get(":status"));
            }
            if (frame instanceof Http2ResetStreamFrame && ((Http2ResetStreamFrame) frame).streamId() == stream) {
                return frames;
            }
            if (frame instanceof Http2GoAway) return frames;
            if (frame instanceof Http2DataFrame && ((Http2DataFrame) frame).streamId() == stream
                && ((Http2DataFrame) frame).endStream() && !badRequestResponse) return frames;
            if (frame instanceof Http2HeadersFrame && ((Http2HeadersFrame) frame).streamId() == stream
                && ((Http2HeadersFrame) frame).endStream()
                && !"400".equals(((Http2HeadersFrame) frame).headers().get(":status"))) return frames;
        }
        fail("No terminal outcome in 64 frames for stream " + stream);
        return frames;
    }

    static void assertInitialRejection(List<LogicalHttp2Frame> frames, int stream) {
        // Selected policy: the mandatory stream error is preceded by a helpful HTTP 400.
        int statusAt = -1;
        int resetAt = -1;
        for (int i = 0; i < frames.size(); i++) {
            var frame = frames.get(i);
            assertFalse(frame instanceof Http2GoAway, "Semantic errors must preserve the connection");
            if (frame instanceof Http2HeadersFrame && ((Http2HeadersFrame) frame).streamId() == stream) {
                assertEquals("400", ((Http2HeadersFrame) frame).headers().get(":status"));
                statusAt = i;
            }
            if (frame instanceof Http2ResetStreamFrame && ((Http2ResetStreamFrame) frame).streamId() == stream) {
                assertEquals(Http2ErrorCode.PROTOCOL_ERROR, ((Http2ResetStreamFrame) frame).errorCodeEnum());
                resetAt = i;
            }
        }
        assertTrue(statusAt >= 0, "Missing HTTP 400 before reset: " + frames);
        assertTrue(resetAt > statusAt, "Missing PROTOCOL_ERROR reset after HTTP 400: " + frames);
    }
}
