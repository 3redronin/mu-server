package io.muserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;

import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;
import static org.junit.jupiter.api.Assertions.assertEquals;

@Timeout(10)
class WebsocketOpcodeTest {
    private MuServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    static Stream<Arguments> reservedOpcodes() {
        return IntStream.of(3, 4, 5, 6, 7, 11, 12, 13, 14, 15).boxed()
            .flatMap(opcode -> Stream.of(Arguments.of(opcode, true), Arguments.of(opcode, false)));
    }

    @ParameterizedTest(name = "opcode {0}, FIN {1}")
    @MethodSource("reservedOpcodes")
    void reservedOpcodesCloseWith1002BeforeDeliveringLaterFrames(int opcode, boolean fin) throws Exception {
        AtomicInteger delivered = new AtomicInteger();
        server = httpServer().addHandler(webSocketHandler((request, headers) -> new SimpleWebSocket() {
            @Override public void onBinary(ByteBuffer message) { delivered.incrementAndGet(); }
            @Override public void onText(String message) throws Exception {
                delivered.incrementAndGet();
                session().sendText(message);
            }
        }).withPingInterval(0, TimeUnit.MILLISECONDS)).start();
        try (WebSocketWireTestSupport client = new WebSocketWireTestSupport(server)) {
            // Queue a valid message after the unknown frame (and its continuation).
            // Ignoring the reserved opcode would incorrectly deliver and echo that message.
            client.output.write(WebSocketWireTestSupport.frame(fin, opcode, new byte[]{42}));
            if (!fin) client.output.write(WebSocketWireTestSupport.frame(true, 0, new byte[]{43}));
            client.output.write(WebSocketWireTestSupport.frame(true, 1, new byte[]{'o', 'k'}));
            client.output.flush();
            byte[] close = client.readFrame(8);
            assertEquals(1002, ((close[0] & 255) << 8) | (close[1] & 255));
            assertEquals(-1, client.input.read(), "Connection must terminate after the protocol error");
            assertEquals(0, delivered.get());
        }
    }
}
