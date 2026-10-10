package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class WebsocketFrameDecoderTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 125, 126, 65535, 65536})
    void arbitraryInputBuffersAndSplitsPreserveSourceAndFrameBoundaries(int length) throws Exception {
        byte[] payload = new byte[length];
        for (int i = 0; i < length; i++) payload[i] = (byte) (i * 31);
        byte[] encoded = WebSocketWireTestSupport.frame(true, 2, payload);
        int headerSize = encoded.length - payload.length;
        for (int kind = 0; kind < 4; kind++) {
            for (int split = 0; split <= encoded.length; split++) {
                // Exhaust small frames; large frames cover every header split and representative payload splits.
                if (length > 126 && split > headerSize + 4 && split < encoded.length - 4
                    && split != encoded.length / 2 && split != 8191 && split != 8192) continue;
                var decoder = new WebsocketFrameDecoder(65536, 65536);
                ByteBuffer source = input(kind, encoded);
                source.limit(split);
                WebsocketFrameDecoder.Frame first = decoder.decode(source);
                assertEquals(split, source.position());
                if (split < encoded.length) {
                    assertNull(first);
                    source.limit(encoded.length);
                    first = decoder.decode(source);
                }
                assertNotNull(first);
                assertEquals(2, first.opcode);
                assertTrue(first.fin);
                assertFalse(first.text);
                assertArrayEquals(payload, bytes(first.payload));
                assertEquals(encoded.length, source.position());
                assertArrayEquals(encoded, bytes(source.rewind()));
                assertNull(decoder.decode(ByteBuffer.allocate(0)));
            }
        }
    }

    @Test
    void stopsAfterOneFrameAndTransfersIndependentPayloadStorage() throws Exception {
        byte[] first = WebSocketWireTestSupport.frame(true, 2, new byte[]{1, 2, 3});
        byte[] second = WebSocketWireTestSupport.frame(true, 2, new byte[]{4, 5});
        ByteBuffer source = ByteBuffer.allocate(first.length + second.length).put(first).put(second).flip();
        var decoder = new WebsocketFrameDecoder(1024, 1024);
        var decoded = Objects.requireNonNull(decoder.decode(source));
        assertEquals(first.length, source.position());
        decoded.payload.put(0, (byte) 9);
        assertArrayEquals(new byte[]{4, 5}, bytes(Objects.requireNonNull(decoder.decode(source)).payload));
        Arrays.fill(source.array(), (byte) 0);
        assertArrayEquals(new byte[]{9, 2, 3}, bytes(decoded.payload));
    }

    @Test
    void singleByteFeedsWorkAcrossAllHeaderSizesAndMaskOffsets() throws Exception {
        for (int length : new int[]{0, 125, 126, 65536}) {
            byte[] payload = new byte[length];
            Arrays.fill(payload, (byte) 0xab);
            byte[] frame = WebSocketWireTestSupport.frame(true, 2, payload);
            var decoder = new WebsocketFrameDecoder(65536, 65536);
            for (int i = 0; i < frame.length; i++) {
                var decoded = decoder.decode(ByteBuffer.wrap(frame, i, 1));
                if (i == frame.length - 1) assertArrayEquals(payload, bytes(Objects.requireNonNull(decoded).payload));
                else assertNull(decoded);
            }
        }
    }

    @Test
    void messageLimitResetsForEveryFinalDataFrameAndExcludesControlFrames() throws Exception {
        var decoder = new WebsocketFrameDecoder(1024, 1024);
        decode(decoder, true, 1, new byte[600]);
        decode(decoder, true, 1, new byte[600]);
        decode(decoder, true, 2, new byte[1024]);
        decode(decoder, false, 1, new byte[1024]);
        decode(decoder, true, 9, new byte[125]);
        decode(decoder, true, 10, new byte[125]);
        assertTrue(decode(decoder, true, 0, new byte[0]).text);
        decode(decoder, true, 2, new byte[1024]);
    }

    @Test
    void aggregateLimitIsCheckedBeforeMaskOrPayloadArrives() throws Exception {
        var decoder = new WebsocketFrameDecoder(1024, 1024);
        decode(decoder, false, 2, new byte[1024]);
        assertInvalid(decoder, new byte[]{(byte) 0x80, (byte) 0x81}, 1009);
        // Message limits are long, independent of a single frame's integer limit.
        var largeMessage = new WebsocketFrameDecoder(1024, Long.MAX_VALUE);
        decode(largeMessage, false, 2, new byte[1024]);
        decode(largeMessage, true, 0, new byte[1024]);
    }

    @Test
    void invalidHeadersFailWithoutWaitingForMaskOrPayloadAndStayFailed() {
        for (byte[] bad : new byte[][]{
            {(byte) 0xc1, (byte) 0x80}, // reserved bit
            {(byte) 0x83, (byte) 0x80}, // reserved opcode
            {(byte) 0x81, 0}, // unmasked
            {9, (byte) 0x80}, // fragmented control
            {(byte) 0x89, (byte) 0xfe}, // oversized control
            {(byte) 0x80, (byte) 0x80}, // unsolicited continuation
            {(byte) 0x88, (byte) 0x81}, // one-byte close
            {(byte) 0x82, (byte) 0xfe, 0, 125}, // nonminimal 16-bit length
            {(byte) 0x82, (byte) 0xff, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff}, // nonminimal 64-bit
            {(byte) 0x82, (byte) 0xff, (byte) 0x80, 0, 0, 0, 0, 0, 0, 0} // signed 64-bit length
        }) assertInvalid(new WebsocketFrameDecoder(1024, 1024), bad, 1002);
        assertInvalid(new WebsocketFrameDecoder(1024, 1024),
            new byte[]{(byte) 0x82, (byte) 0xfe, 4, 1}, 1009);
        assertInvalid(new WebsocketFrameDecoder(Integer.MAX_VALUE, Long.MAX_VALUE),
            new byte[]{(byte) 0x82, (byte) 0xff, 0, 0, 0, 0, (byte) 0x80, 0, 0, 0}, 1009);
    }

    @Test
    void dataMessagesCannotInterleaveEvenAroundControlFrames() throws Exception {
        for (int first : new int[]{1, 2}) {
            for (int next : new int[]{1, 2}) {
                var decoder = new WebsocketFrameDecoder(1024, 1024);
                decode(decoder, false, first, new byte[0]);
                decode(decoder, true, 9, new byte[0]);
                assertInvalid(decoder, new byte[]{(byte) (0x80 | next), (byte) 0x80}, 1002);
            }
        }
    }

    @Test
    void textValidationSpansFramesAndControlPayloadsAreIndependent() throws Exception {
        var decoder = new WebsocketFrameDecoder(1024, 1024);
        byte[] utf8 = "\uD83D\uDC4B".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < utf8.length; i++) {
            assertTrue(decode(decoder, false, i == 0 ? 1 : 0, new byte[]{utf8[i]}).text);
            assertFalse(decode(decoder, true, 9, new byte[]{(byte) 0xff}).text);
        }
        assertTrue(decode(decoder, true, 0, new byte[0]).text);
        decode(decoder, true, 1, new byte[]{'a'});
        decode(decoder, true, 2, new byte[]{(byte) 0xff});
        decode(decoder, false, 1, new byte[]{(byte) 0xf0});
        assertInvalid(decoder, WebSocketWireTestSupport.frame(true, 0, new byte[0]), 1007);
    }

    @Test
    void invalidTextAndClosePayloadsFailAsSoonAsTheirInvalidPrefixArrives() throws Exception {
        for (int opcode : new int[]{1, 8}) {
            byte[] payload = opcode == 1 ? new byte[]{(byte) 0xf4, (byte) 0x90, 0, 0}
                : new byte[]{3, (byte) 232, (byte) 0xf4, (byte) 0x90, 0, 0};
            byte[] frame = WebSocketWireTestSupport.frame(true, opcode, payload);
            assertInvalid(new WebsocketFrameDecoder(1024, 1024), Arrays.copyOf(frame, frame.length - 2), 1007);
        }
        byte[] frame = WebSocketWireTestSupport.frame(true, 8, new byte[]{3, (byte) 237, 'a'}); // 1005
        assertInvalid(new WebsocketFrameDecoder(1024, 1024), Arrays.copyOf(frame, frame.length - 1), 1002);
    }

    @Test
    void validatesCloseCodesAndReasonBeforePublishingClose() throws Exception {
        for (int code : new int[]{0, 999, 1004, 1005, 1006, 1015, 1016, 2999, 5000, 65535}) {
            assertInvalid(new WebsocketFrameDecoder(1024, 1024),
                WebSocketWireTestSupport.frame(true, 8, new byte[]{(byte) (code >> 8), (byte) code}), 1002);
        }
        for (int code : new int[]{1000, 1003, 1007, 1011, 1012, 1013, 1014, 3000, 4999}) {
            var decoder = new WebsocketFrameDecoder(1024, 1024);
            ByteBuffer payload = ByteBuffer.allocate(6).putShort((short) code).put("\uD83D\uDC4B".getBytes(StandardCharsets.UTF_8)).flip();
            var close = decode(decoder, true, 8, bytes(payload));
            assertEquals(code, close.closeCode);
            assertEquals("\uD83D\uDC4B", close.closeReason);
            decoder.endOfInput();
            ByteBuffer tail = ByteBuffer.wrap(new byte[]{1, 2});
            assertNull(decoder.decode(tail));
            assertEquals(0, tail.position());
        }
        var empty = decode(new WebsocketFrameDecoder(1024, 1024), true, 8, new byte[0]);
        assertEquals(1005, empty.closeCode);
        assertEquals("", empty.closeReason);
        assertInvalid(new WebsocketFrameDecoder(1024, 1024),
            WebSocketWireTestSupport.frame(true, 8, new byte[]{3, (byte) 232, (byte) 0xc2}), 1007);
    }

    @Test
    void eofAtEveryFrameBoundaryWithoutCloseIsStickyDisconnect() throws Exception {
        for (int opcode : new int[]{1, 2, 8, 9}) {
            byte[] frame = WebSocketWireTestSupport.frame(true, opcode,
                opcode == 8 ? new byte[]{3, (byte) 232, 'x'} : new byte[126]);
            // Control frames may not have extended lengths.
            if (opcode == 9) frame = WebSocketWireTestSupport.frame(true, opcode, new byte[125]);
            for (int size = 0; size <= frame.length; size++) {
                var decoder = new WebsocketFrameDecoder(1024, 1024);
                decoder.decode(ByteBuffer.wrap(frame, 0, size));
                if (opcode == 8 && size == frame.length) decoder.endOfInput();
                else {
                    var failure = assertThrows(ClientDisconnectedException.class, decoder::endOfInput);
                    assertSame(failure, assertThrows(ClientDisconnectedException.class, decoder::endOfInput));
                    ByteBuffer more = ByteBuffer.wrap(frame);
                    assertSame(failure, assertThrows(ClientDisconnectedException.class, () -> decoder.decode(more)));
                    assertEquals(0, more.position());
                }
            }
        }
    }

    private static WebsocketFrameDecoder.Frame decode(WebsocketFrameDecoder decoder, boolean fin, int opcode, byte[] payload) throws Exception {
        return Objects.requireNonNull(decoder.decode(ByteBuffer.wrap(WebSocketWireTestSupport.frame(fin, opcode, payload))));
    }

    private static void assertInvalid(WebsocketFrameDecoder decoder, byte[] bytes, int code) {
        var failure = assertThrows(WebsocketFrameDecoder.InvalidFrame.class, () -> decoder.decode(ByteBuffer.wrap(bytes)));
        assertEquals(code, failure.closeCode);
        ByteBuffer more = ByteBuffer.wrap(new byte[]{1, 2, 3});
        assertSame(failure, assertThrows(WebsocketFrameDecoder.InvalidFrame.class, () -> decoder.decode(more)));
        assertEquals(0, more.position());
        assertSame(failure, assertThrows(WebsocketFrameDecoder.InvalidFrame.class, decoder::endOfInput));
    }

    private static byte[] bytes(ByteBuffer source) {
        byte[] bytes = new byte[source.remaining()];
        source.duplicate().get(bytes);
        return bytes;
    }

    private static ByteBuffer input(int kind, byte[] bytes) {
        if (kind == 0) return ByteBuffer.wrap(bytes.clone());
        if (kind == 1) return ByteBuffer.allocateDirect(bytes.length).put(bytes).flip();
        if (kind == 2) return ByteBuffer.wrap(bytes).asReadOnlyBuffer();
        return ByteBuffer.allocate(bytes.length + 7).position(7).put(bytes).flip().position(7).slice();
    }
}
