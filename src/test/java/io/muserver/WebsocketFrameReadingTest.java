package io.muserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static scaffolding.MuAssert.assertEventually;

@Timeout(15)
class WebsocketFrameReadingTest {
    @org.junit.jupiter.api.Test
    void failedTransportReadsReportDisconnectWithCauseButKeepTimeoutsDistinct() {
        for (java.io.IOException failure : new java.io.IOException[]{new java.net.SocketException("reset"),
            new javax.net.ssl.SSLException("truncated TLS record"), new java.io.IOException("closed")}) {
            var input = new java.io.InputStream() {
                @Override public int read() throws java.io.IOException { throw failure; }
            };
            ClientDisconnectedException disconnected = org.junit.jupiter.api.Assertions.assertThrows(ClientDisconnectedException.class,
                () -> WebsocketConnection.readAtLeast(ByteBuffer.allocate(16).flip(), input, 2));
            org.junit.jupiter.api.Assertions.assertSame(failure, disconnected.getCause());
        }
        java.io.IOException timeout = new java.net.SocketTimeoutException("deadline");
        var input = new java.io.InputStream() {
            @Override public int read() throws java.io.IOException { throw timeout; }
        };
        org.junit.jupiter.api.Assertions.assertSame(timeout, org.junit.jupiter.api.Assertions.assertThrows(java.net.SocketTimeoutException.class,
            () -> WebsocketConnection.readAtLeast(ByteBuffer.allocate(16).flip(), input, 2)));
    }

    private MuServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void messageLimitsResetAndControlFramesDoNotConsumeMessageBudget(boolean channel) throws Exception {
        MuServerBuilder builder = httpServer();
        builder.useChannelTransport = channel;
        server = builder.addHandler(webSocketHandler((request, headers) -> new SimpleWebSocket() {
            @Override public void onText(String message) throws Exception { session().sendText(message); }
            @Override public void onBinary(ByteBuffer message) throws Exception { session().sendBinary(message); }
        }).withMaxMessageLength(1024).withPingInterval(0, TimeUnit.MILLISECONDS)).start();
        try (WebSocketWireTestSupport client = new WebSocketWireTestSupport(server)) {
            byte[] text = "a".repeat(600).getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < 3; i++) {
                client.send(true, 1, text);
                assertArrayEquals(text, client.readFrame(1));
            }
            byte[] full = "b".repeat(1024).getBytes(StandardCharsets.UTF_8);
            client.send(false, 1, full);
            client.send(true, 9, new byte[]{1});
            assertArrayEquals(new byte[]{1}, client.readFrame(10));
            client.send(true, 0, new byte[0]);
            assertArrayEquals(full, client.readFrame(1));
            client.send(true, 2, full);
            assertArrayEquals(full, client.readFrame(2));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsNonminimalLengthsBeforeReceivingMaskAndPayload(boolean channel) throws Exception {
        MuServerBuilder builder = httpServer();
        builder.useChannelTransport = channel;
        server = builder.addHandler(webSocketHandler((request, headers) -> new SimpleWebSocket() {
            @Override public void onText(String message) { org.junit.jupiter.api.Assertions.fail("Invalid frame delivered"); }
            @Override public void onBinary(ByteBuffer message) { org.junit.jupiter.api.Assertions.fail("Invalid frame delivered"); }
        })
            .withPingInterval(0, TimeUnit.MILLISECONDS)).start();
        for (byte[] frame : new byte[][]{
            {(byte) 0x81, (byte) 0xfe, 0, 1},
            {(byte) 0x82, (byte) 0xff, 0, 0, 0, 0, 0, 0, (byte) 0xff, (byte) 0xff}
        }) {
            try (WebSocketWireTestSupport client = new WebSocketWireTestSupport(server)) {
                client.output.write(frame);
                client.output.flush();
                byte[] close = client.readFrame(8);
                assertEquals(1002, ByteBuffer.wrap(close).getShort() & 0xffff);
            }
        }
    }

    @ParameterizedTest(name = "opcode {0}, payload {1} bytes, split {2}")
    @CsvSource({"9,125,1", "9,125,7", "1,126,1", "1,126,9", "1,8192,9",
        "2,8192,9", "2,65536,1", "2,65536,15", "2,65536,8191"})
    void unreadFrameBytesSurviveSocketRefills(int opcode, int length, int split) throws Exception {
        server = httpServer().addHandler(webSocketHandler((request, headers) -> new SimpleWebSocket() {
            @Override public void onText(String message) throws Exception { session().sendText(message); }
            @Override public void onBinary(ByteBuffer message) throws Exception { session().sendBinary(message); }
        }).withMaxFramePayloadLength(1024 * 1024).withPingInterval(0, TimeUnit.MILLISECONDS)).start();
        byte[] payload = new byte[length];
        byte[] alphabet = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < payload.length; i++) payload[i] = alphabet[i % alphabet.length];
        try (WebSocketWireTestSupport client = new WebSocketWireTestSupport(server)) {
            byte[] frame = WebSocketWireTestSupport.frame(true, opcode, payload);
            client.output.write(frame, 0, split);
            client.output.flush();
            // Wait until the server has consumed the prefix before sending the rest.
            // This forces separate InputStream reads without relying on TCP packet boundaries or sleeps.
            assertEventually(() -> server.stats().bytesRead(), equalTo((long) client.handshakeBytes + split));
            client.output.write(frame, split, frame.length - split);
            client.output.flush();
            assertArrayEquals(payload, client.readFrame(opcode == 9 ? 10 : opcode));
            // A following frame must also survive compaction and reuse of the same read buffer.
            client.send(true, 9, new byte[]{1, 2, 3});
            assertArrayEquals(new byte[]{1, 2, 3}, client.readFrame(10));
            client.send(true, 8, new byte[]{3, (byte) 232});
            assertArrayEquals(new byte[]{3, (byte) 232}, client.readFrame(8));
        }
    }
}
