package io.muserver;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.getHelloHeaders;
import static org.junit.jupiter.api.Assertions.*;

/** RFC 9113 section 6.5.3: SETTINGS ACK confirms application to the outbound encoder. */
@Timeout(15)
class HpackSettingsAckTest {
    static Stream<Arguments> transitions() {
        return HpackTableSizeTransitionTest.transitions();
    }

    @ParameterizedTest
    @MethodSource("transitions")
    void acknowledgedLimitsApplyToAnExistingStreamAndAreNotRepeated(int[] sizes, String expectedPrefix, int finalSize) throws Exception {
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        try (MuServer server = httpsServer().withInterface("127.0.0.1")
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler(Method.GET, "/hello", (request, response, params) -> {
                response.status(204);
                response.headers().set("x-test", "unchanged");
            })
            .addHandler(Method.GET, "/pending", (request, response, params) -> {
                handlerStarted.countDown();
                assertTrue(releaseResponse.await(5, TimeUnit.SECONDS), "Timed out waiting for acknowledged SETTINGS");
                response.status(204);
                response.headers().set("x-test", "unchanged");
            }).start();
             H2Client client = new H2Client();
             H2ClientConnection connection = client.connect(server)) {
            connection.socket().setSoTimeout(5000);
            HpackTable table = new HpackTable(4096);
            FieldBlockDecoder decoder = new FieldBlockDecoder(table, 8192, 8192);
            int port = server.uri().getPort();
            connection.handshake().writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(port))).flush();
            assertResponse(connection, decoder, 1, "");

            FieldBlock pending = getHelloHeaders(port);
            pending.set(":path", "/pending");
            connection.writeFrame(new Http2HeadersFrame(3, true, pending)).flush();
            assertTrue(handlerStarted.await(5, TimeUnit.SECONDS), "Request must already be in flight");
            for (int size : sizes) {
                connection.writeRaw(setting(size)).flush();
                assertEquals(Http2Settings.ACK, connection.readLogicalFrame(), "ACK must precede release of the response");
            }
            decoder.changeTableSize(finalSize);
            releaseResponse.countDown();
            assertResponse(connection, decoder, 3, expectedPrefix);
            assertEquals(finalSize, table.maxSize());

            connection.writeFrame(new Http2HeadersFrame(5, true, getHelloHeaders(port))).flush();
            assertResponse(connection, decoder, 5, "");
        } finally {
            releaseResponse.countDown();
        }
    }

    private static byte[] setting(int size) {
        // Independent frame serialization: six-byte SETTINGS_HEADER_TABLE_SIZE payload.
        return ByteBuffer.allocate(15).put(new byte[] {0, 0, 6, 4, 0, 0, 0, 0, 0})
            .putShort((short) 1).putInt(size).array();
    }

    private static void assertResponse(H2ClientConnection connection, FieldBlockDecoder decoder,
                                       int stream, String expectedPrefix) throws Exception {
        Http2FrameHeader header = connection.readFrameHeader();
        assertEquals(Http2FrameType.HEADERS, header.frameType());
        assertEquals(stream, header.streamId());
        assertEquals(5, header.flags() & 5, "Bodyless response must set END_HEADERS and END_STREAM");
        byte[] payload = connection.readRawPayload(header);
        byte[] prefix = hexToByteArray(expectedPrefix);
        assertTrue(payload.length > prefix.length);
        assertArrayEquals(prefix, Arrays.copyOf(payload, prefix.length));
        assertNotEquals(0x20, payload[prefix.length] & 0xe0, "Exactly the expected size updates must precede the fields");
        FieldBlock fields = decoder.decodeFrom(ByteBuffer.wrap(payload));
        assertEquals("204", fields.get(":status"));
        assertEquals("unchanged", fields.get("x-test"));
    }
}
