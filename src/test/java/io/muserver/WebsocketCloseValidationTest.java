package io.muserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.MuAssert.assertEventually;

@Timeout(10)
class WebsocketCloseValidationTest {
    private MuServer server;
    private final RecordingSocket handler = new RecordingSocket();

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    private WebSocketWireTestSupport connect() throws Exception {
        server = httpServer().addHandler(webSocketHandler((request, headers) -> handler)
            .withPingInterval(0, TimeUnit.MILLISECONDS)).start();
        return new WebSocketWireTestSupport(server);
    }

    private static byte[] closePayload(int code, byte[] reason) {
        return ByteBuffer.allocate(2 + reason.length).putShort((short) code).put(reason).array();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 999, 1004, 1005, 1006, 1015, 1016, 1100, 2000, 2999, 5000, 32768, 65535})
    void invalidCloseCodesAreRejectedBeforeTheApplicationCallback(int code) throws Exception {
        rejectedClose(closePayload(code, new byte[0]), 1002);
    }

    static Stream<byte[]> invalidReasons() {
        return Stream.of(new byte[]{(byte) 0xc3, 0x28}, new byte[]{(byte) 0xe2, (byte) 0x82},
            new byte[]{(byte) 0x80}, new byte[]{(byte) 0xc0, (byte) 0xaf},
            new byte[]{(byte) 0xed, (byte) 0xa0, (byte) 0x80},
            new byte[]{(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80});
    }

    @ParameterizedTest
    @MethodSource("invalidReasons")
    void invalidUTF8ReasonsAreRejectedBeforeTheApplicationCallback(byte[] reason) throws Exception {
        rejectedClose(closePayload(1000, reason), 1007);
    }

    @Test
    void aOneByteClosePayloadIsAProtocolError() throws Exception {
        rejectedClose(new byte[]{3}, 1002);
    }

    private void rejectedClose(byte[] payload, int expectedCode) throws Exception {
        try (WebSocketWireTestSupport client = connect()) {
            client.send(true, 8, payload);
            byte[] response = client.readFrame(8);
            assertTrue(response.length >= 2, "Expected an explicit error close code");
            assertEquals(expectedCode, ((response[0] & 255) << 8) | (response[1] & 255));
            assertEquals(-1, client.input.read());
            assertEventually(handler::state, equalTo(WebsocketSessionState.ERRORED));
            assertEquals(0, handler.clientCloseCallbacks.get());
            assertEquals(1, handler.errors.get());
            assertFalse(handler.session().closeReceived(), "Invalid close is not a completed peer close");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1000, 1001, 1002, 1003, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014,
        3000, 3999, 4000, 4999})
    void validCodesAndUTF8ReasonsStillCompleteTheClosingHandshake(int code) throws Exception {
        byte[] payload = closePayload(code, "Goodbye 世界 👋".getBytes(StandardCharsets.UTF_8));
        try (WebSocketWireTestSupport client = connect()) {
            client.send(true, 8, payload);
            assertArrayEquals(payload, client.readFrame(8));
            assertEquals(-1, client.input.read());
            assertEventually(handler::state, equalTo(WebsocketSessionState.CLIENT_CLOSED));
            assertEquals(1, handler.clientCloseCallbacks.get());
            assertEquals(0, handler.errors.get());
            assertTrue(handler.session().closeReceived());
        }
    }

    @Test
    void anEmptyClosePayloadStillCompletesWithoutSendingASyntheticCode() throws Exception {
        try (WebSocketWireTestSupport client = connect()) {
            client.send(true, 8, new byte[0]);
            assertArrayEquals(new byte[0], client.readFrame(8));
            assertEventually(handler::state, equalTo(WebsocketSessionState.CLIENT_CLOSED));
            assertEquals(1, handler.clientCloseCallbacks.get());
            assertEquals(0, handler.errors.get());
        }
    }

    private static class RecordingSocket extends SimpleWebSocket {
        final AtomicInteger clientCloseCallbacks = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        @Override public void onText(String message) { fail("Unexpected text callback"); }
        @Override public void onBinary(ByteBuffer message) { fail("Unexpected binary callback"); }
        @Override public void onClientClosed(int code, String reason) throws Exception {
            clientCloseCallbacks.incrementAndGet();
            super.onClientClosed(code, reason);
        }
        @Override public void onError(Throwable cause) throws Exception {
            errors.incrementAndGet();
            super.onError(cause);
        }
    }
}
