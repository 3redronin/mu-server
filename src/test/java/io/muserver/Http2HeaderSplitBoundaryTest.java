package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static org.junit.jupiter.api.Assertions.*;

/** RFC 9113 section 4.3: HPACK decoding is independent of frame and transport boundaries. */
class Http2HeaderSplitBoundaryTest {
    private static byte[] block() {
        // Table size 256, RFC 7541 C.4.1 request, then an indexed literal with a 130-byte value.
        // Includes multi-byte size/length integers, a literal name, and a Huffman string.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.writeBytes(hexToByteArray("3fe101 828684418cf1e3c2e5f23a6ba0ab90f4ff 4006782d6c6f6e677f03"));
        bytes.writeBytes("z".repeat(130).getBytes(StandardCharsets.US_ASCII));
        return bytes.toByteArray();
    }

    static Stream<Arguments> boundaries() {
        return IntStream.rangeClosed(0, block().length).boxed().flatMap(split -> Stream.of(
            Arguments.of(split, 1, false), Arguments.of(split, 8192, true)));
    }

    @ParameterizedTest(name = "split={0}, read={1}, END_STREAM={2}")
    @MethodSource("boundaries")
    void everyByteBoundaryPreservesTheBlockAndFollowingFrame(int split, int readSize, boolean endStream) throws Exception {
        byte[] block = block();
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        frame(tail, 9, 4, 1, Arrays.copyOfRange(block, split, block.length));
        ping(tail);
        ByteBuffer initial = ByteBuffer.allocate(512);
        initial.put(block, 0, split).flip();
        check(new Http2FrameHeader(split, Http2FrameType.HEADERS, endStream ? 1 : 0, 1), initial,
            input(tail.toByteArray(), readSize), endStream);
    }

    @Test
    void oneByteContinuationsAndEmptyIntermediateFramesAreReassembled() throws Exception {
        byte[] block = block();
        ByteArrayOutputStream tail = new ByteArrayOutputStream();
        frame(tail, 9, 0, 1, new byte[0]);
        for (int i = 0; i < block.length; i++) {
            frame(tail, 9, i == block.length - 1 ? 4 : 0, 1, new byte[] {block[i]});
        }
        ping(tail);
        check(new Http2FrameHeader(0, Http2FrameType.HEADERS, 1, 1), ByteBuffer.allocate(512).flip(),
            input(tail.toByteArray(), 3), true);
    }

    @Test
    void paddingAndPriorityAreExcludedWhenContinuationIsAlreadyBuffered() throws Exception {
        byte[] block = block();
        int split = 13;
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        wire.write(2); // Pad length.
        wire.writeBytes(new byte[] {0, 0, 0, 3, 15}); // Priority dependency and weight.
        wire.write(block, 0, split);
        wire.writeBytes(new byte[] {0, 0});
        frame(wire, 9, 4, 1, Arrays.copyOfRange(block, split, block.length));
        ping(wire);
        ByteBuffer buffer = ByteBuffer.allocate(512);
        buffer.put(wire.toByteArray()).flip();
        check(new Http2FrameHeader(split + 8, Http2FrameType.HEADERS, 0x29, 1), buffer,
            new ByteArrayInputStream(new byte[0]), true);
    }

    private static void check(Http2FrameHeader header, ByteBuffer buffer, ByteArrayInputStream input, boolean endStream) throws Exception {
        HpackTable table = new HpackTable(4096);
        FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192);
        Http2HeadersFrame decoded = Http2HeadersFrame.readLogicalFrame(header, decoder, buffer, input);
        assertEquals(1, decoded.streamId());
        assertEquals(endStream, decoded.endStream());
        assertEquals(5, decoded.headers().size());
        assertEquals("GET", decoded.headers().get(":method"));
        assertEquals("http", decoded.headers().get(":scheme"));
        assertEquals("/", decoded.headers().get(":path"));
        assertEquals("www.example.com", decoded.headers().get(":authority"));
        assertEquals("z".repeat(130), decoded.headers().get("x-long"));
        assertEquals(256, table.maxSize());
        assertEquals(225, table.dynamicTableSizeInBytes());
        // A subsequent header block must be able to use the entries just inserted.
        FieldBlock reused = decoder.decodeFrom(ByteBuffer.wrap(hexToByteArray("be bf")));
        assertEquals("z".repeat(130), reused.get("x-long"));
        assertEquals("www.example.com", reused.get(":authority"));

        Mutils.readAtLeast(buffer, input, 9);
        Http2FrameHeader next = Http2FrameHeader.readFrom(buffer);
        assertEquals(Http2FrameType.PING, next.frameType());
        assertEquals(0, next.streamId());
        assertEquals(8, next.length());
        Mutils.readAtLeast(buffer, input, 8);
        assertEquals(42L, buffer.getLong());
        assertFalse(buffer.hasRemaining());
        assertEquals(-1, input.read());
    }

    private static ByteArrayInputStream input(byte[] bytes, int readSize) {
        return new ByteArrayInputStream(bytes) {
            @Override public synchronized int read(byte[] target, int offset, int length) {
                return super.read(target, offset, Math.min(length, readSize));
            }
        };
    }

    private static void frame(ByteArrayOutputStream out, int type, int flags, int stream, byte[] payload) {
        out.writeBytes(ByteBuffer.allocate(9).put((byte) (payload.length >>> 16)).put((byte) (payload.length >>> 8))
            .put((byte) payload.length).put((byte) type).put((byte) flags).putInt(stream).array());
        out.writeBytes(payload);
    }

    private static void ping(ByteArrayOutputStream out) {
        frame(out, 6, 0, 0, ByteBuffer.allocate(8).putLong(42).array());
    }
}
