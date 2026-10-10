package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

class Http2HeaderBlockDecoderTest {
    private static byte[] block() {
        var bytes = new ByteArrayOutputStream();
        // Includes table-size and string-length integers spanning bytes, Huffman strings,
        // and two dynamic entries that later streams must be able to reference.
        bytes.writeBytes(hexToByteArray("3fe101 828684418cf1e3c2e5f23a6ba0ab90f4ff 4006782d6c6f6e677f03"));
        bytes.writeBytes("z".repeat(130).getBytes(US_ASCII));
        return bytes.toByteArray();
    }

    @ParameterizedTest @ValueSource(strings = {"heap", "direct", "readonly"})
    void everySplitConsumesOnlyItsFrameAndCopiesIncompleteBlocks(String kind) throws Exception {
        byte[] encoded = block();
        for (int split = 0; split <= encoded.length; split++) {
            var table = new HpackTable(4096);
            var hpack = new FieldBlockDecoder(table, 8192, 8192);
            var decoder = new Http2HeaderBlockDecoder(header(split, Http2FrameType.HEADERS, 1, 1), hpack, 512);
            Feed first = new Feed(Arrays.copyOfRange(encoded, 0, split), kind);
            assertNull(decoder.payload(first.source));
            first.checkEndAndOverwrite();
            decoder.continuation(header(encoded.length - split, Http2FrameType.CONTINUATION, 4, 1));
            Feed last = new Feed(Arrays.copyOfRange(encoded, split, encoded.length), kind);
            Http2HeadersFrame frame = decoder.payload(last.source);
            last.checkEndAndOverwrite();
            assertNotNull(frame);
            assertEquals(1, frame.streamId());
            assertTrue(frame.endStream());
            assertEquals("GET", frame.headers().get(":method"));
            assertEquals("/", frame.headers().get(":path"));
            assertEquals("www.example.com", frame.headers().get(":authority"));
            assertEquals("z".repeat(130), frame.headers().get("x-long"));
            assertEquals(256, table.maxSize());
            assertEquals(225, table.dynamicTableSizeInBytes());
            assertReusable(hpack);
            assertThrows(IllegalStateException.class, () -> decoder.payload(ByteBuffer.allocate(0)));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"heap", "direct", "readonly"})
    void aCompleteInitialFrameNeedsNoAggregationStorage(String kind) throws Exception {
        var hpack = new FieldBlockDecoder(new HpackTable(4096), 8192, 8192);
        byte[] encoded = block();
        var decoder = new Http2HeaderBlockDecoder(header(encoded.length, Http2FrameType.HEADERS, 4, 1), hpack, 1);
        Feed feed = new Feed(encoded, kind);
        Http2HeadersFrame frame = decoder.payload(feed.source);
        feed.checkEndAndOverwrite();
        assertNotNull(frame);
        assertFalse(frame.endStream());
        assertEquals("www.example.com", frame.headers().get(":authority"));
        assertReusable(hpack);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void continuationOrderingIsCheckedBeforePayloadAndErrorsStayTerminal(boolean wrongStream) throws Exception {
        var decoder = new Http2HeaderBlockDecoder(header(0, Http2FrameType.HEADERS, 1, 1),
            new FieldBlockDecoder(new HpackTable(4096), 8192, 8192), 512);
        assertNull(decoder.payload(ByteBuffer.allocate(0)));
        Http2FrameHeader invalid = wrongStream ? header(16, Http2FrameType.CONTINUATION, 4, 3)
            : header(8, Http2FrameType.PING, 0, 0);
        Http2Exception failure = assertThrows(Http2Exception.class, () -> decoder.continuation(invalid));
        assertEquals(Http2Level.CONNECTION, failure.errorType());
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, failure.errorCode());
        assertSame(failure, assertThrows(Http2Exception.class, () -> decoder.continuation(header(0, Http2FrameType.CONTINUATION, 4, 1))));
        assertSame(failure, assertThrows(Http2Exception.class, () -> decoder.payload(ByteBuffer.allocate(0))));
    }

    @Test
    void aggregateLimitIsCheckedAtTheContinuationHeader() throws Exception {
        var decoder = new Http2HeaderBlockDecoder(header(5, Http2FrameType.HEADERS, 1, 1),
            new FieldBlockDecoder(new HpackTable(4096), 8192, 8192), 6);
        assertNull(decoder.payload(ByteBuffer.wrap(Arrays.copyOf(block(), 5))));
        Http2Exception failure = assertThrows(Http2Exception.class,
            () -> decoder.continuation(header(2, Http2FrameType.CONTINUATION, 4, 1)));
        assertEquals(Http2Level.CONNECTION, failure.errorType());
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, failure.errorCode());
        assertSame(failure, assertThrows(Http2Exception.class, () -> decoder.payload(ByteBuffer.allocate(2))));
    }

    @Test
    void emptyContinuationsCannotEvadeTheWorkLimit() throws Exception {
        var decoder = new Http2HeaderBlockDecoder(header(0, Http2FrameType.HEADERS, 1, 1),
            new FieldBlockDecoder(new HpackTable(4096), 8192, 8192), 512);
        assertNull(decoder.payload(ByteBuffer.allocate(0)));
        for (int i = 0; i < 128; i++) {
            decoder.continuation(header(0, Http2FrameType.CONTINUATION, 0, 1));
            assertNull(decoder.payload(ByteBuffer.allocate(0)));
        }
        Http2Exception failure = assertThrows(Http2Exception.class,
            () -> decoder.continuation(header(0, Http2FrameType.CONTINUATION, 4, 1)));
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, failure.errorCode());
    }

    @ParameterizedTest @ValueSource(strings = {"priority", "headers", "both"})
    void recoverableErrorsConsumeTheBlockAndPreserveHpackForTheNextStream(String invalid) throws Exception {
        boolean priority = !invalid.equals("headers");
        var hpack = new FieldBlockDecoder(new HpackTable(4096), 8192, invalid.equals("priority") ? 8192 : 300);
        byte[] prefix = priority ? new byte[]{2, 0, 0, 0, 1, 0, 0, 0} : new byte[0];
        var decoder = new Http2HeaderBlockDecoder(header(prefix.length, Http2FrameType.HEADERS, priority ? 0x29 : 1, 1), hpack, 512);
        assertNull(decoder.payload(ByteBuffer.wrap(prefix)));
        byte[] encoded = block();
        decoder.continuation(header(encoded.length, Http2FrameType.CONTINUATION, 4, 1));
        Feed last = new Feed(encoded, "readonly");
        Exception failure = assertThrows(Exception.class, () -> decoder.payload(last.source));
        last.checkEndAndOverwrite();
        if (priority) {
            Http2Exception error = assertInstanceOf(Http2Exception.class, failure);
            assertEquals(Http2Level.STREAM, error.errorType());
            assertEquals(Http2ErrorCode.PROTOCOL_ERROR, error.errorCode());
            assertEquals(1, error.streamId());
        } else assertEquals(431, assertInstanceOf(HttpException.class, failure).status().code());
        assertSame(failure, assertThrows(Exception.class, () -> decoder.payload(ByteBuffer.allocate(0))));
        assertReusable(hpack);
    }

    @Test
    void compressionFailureTakesPrecedenceOverDeferredStreamErrors() throws Exception {
        var decoder = new Http2HeaderBlockDecoder(header(5, Http2FrameType.HEADERS, 0x21, 1),
            new FieldBlockDecoder(new HpackTable(4096), 8192, 8192), 512);
        assertNull(decoder.payload(ByteBuffer.wrap(new byte[]{0, 0, 0, 1, 0})));
        decoder.continuation(header(1, Http2FrameType.CONTINUATION, 4, 1));
        Feed corrupt = new Feed(new byte[]{(byte) 0xff}, "direct");
        Http2Exception failure = assertThrows(Http2Exception.class, () -> decoder.payload(corrupt.source));
        corrupt.checkEndAndOverwrite();
        assertEquals(Http2Level.CONNECTION, failure.errorType());
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, failure.errorCode());
    }

    private static void assertReusable(FieldBlockDecoder hpack) throws Exception {
        var next = new Http2HeaderBlockDecoder(header(2, Http2FrameType.HEADERS, 5, 3), hpack, 512);
        Http2HeadersFrame decoded = next.payload(ByteBuffer.wrap(hexToByteArray("be bf")));
        assertNotNull(decoded);
        assertEquals(3, decoded.streamId());
        assertEquals("z".repeat(130), decoded.headers().get("x-long"));
        assertEquals("www.example.com", decoded.headers().get(":authority"));
    }

    private static Http2FrameHeader header(int length, Http2FrameType type, int flags, int stream) {
        return new Http2FrameHeader(length, type, flags, stream);
    }

    private static final class Feed {
        final ByteBuffer storage;
        final ByteBuffer source;
        Feed(byte[] bytes, String kind) {
            storage = kind.equals("direct") ? ByteBuffer.allocateDirect(bytes.length + 4) : ByteBuffer.allocate(bytes.length + 4);
            storage.position(2).put(bytes).put((byte) 42).flip().position(1);
            ByteBuffer slice = storage.slice();
            slice.position(1);
            source = kind.equals("readonly") ? slice.asReadOnlyBuffer() : slice;
        }
        void checkEndAndOverwrite() {
            assertEquals(1, source.remaining(), "Only the next frame's sentinel must remain");
            assertEquals(42, source.get());
            storage.clear();
            while (storage.hasRemaining()) storage.put((byte) 0);
        }
    }
}
