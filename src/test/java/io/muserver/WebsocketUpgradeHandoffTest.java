package io.muserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

@Timeout(10)
class WebsocketUpgradeHandoffTest {
    private MuServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 8192})
    void upgradeThenBatchedMessagesPreservesPayloadsAndFrameBoundaries(int handshakeWriteSize) throws Exception {
        server = httpServer().withInterface("127.0.0.1").addHandler(webSocketHandler((request, headers) -> new SimpleWebSocket() {
            @Override public void onBinary(ByteBuffer message) {
                throw new AssertionError("Expected text messages only");
            }
            @Override public void onText(String message) throws Exception {
                session().sendText(message);
            }
        }).withPingInterval(0, TimeUnit.MILLISECONDS)).start();

        // The client waits for 101 before sending frames. TCP may coalesce handshake writes.
        try (WebSocketWireTestSupport client = new WebSocketWireTestSupport(server, handshakeWriteSize)) {
            byte[] first = "Hello after upgrade".getBytes(UTF_8);
            byte[] second = "Second message: \u4f60\u597d".getBytes(UTF_8);
            ByteArrayOutputStream batch = new ByteArrayOutputStream();
            batch.write(WebSocketWireTestSupport.frame(true, 1, first));
            batch.write(WebSocketWireTestSupport.frame(true, 1, second));
            client.output.write(batch.toByteArray());
            client.output.flush();
            assertArrayEquals(first, client.readFrame(1));
            assertArrayEquals(second, client.readFrame(1));

            byte[] ping = "still connected".getBytes(UTF_8);
            client.send(true, 9, ping);
            assertArrayEquals(ping, client.readFrame(10));
            byte[] close = {3, (byte) 232}; // Normal closure (1000).
            client.send(true, 8, close);
            assertArrayEquals(close, client.readFrame(8));
        }
    }
}
