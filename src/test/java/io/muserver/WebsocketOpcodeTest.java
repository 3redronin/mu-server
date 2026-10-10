package io.muserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.Arguments;

import java.io.ByteArrayOutputStream;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class WebsocketOpcodeTest {
    private MuServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }

    @ParameterizedTest @CsvSource({"1,false", "1,true", "2,false", "2,true"})
    void competingFragmentKindsFailWithoutWritingOrDisturbingTheActiveMessage(int opcode, boolean channel) throws Exception {
        var connected = new java.util.concurrent.CompletableFuture<MuWebSocketSession>();
        MuServerBuilder builder = httpServer();
        builder.useChannelTransport = channel;
        server = builder.addHandler(webSocketHandler((req, headers) -> new SimpleWebSocket() {
            @Override public void onConnect(MuWebSocketSession session) throws Exception {
                super.onConnect(session);
                connected.complete(session);
            }
            @Override public void onText(String text) { }
            @Override public void onBinary(ByteBuffer bytes) { }
        }).withPingInterval(0, TimeUnit.MILLISECONDS)).start();
        try (WebSocketWireTestSupport client = new WebSocketWireTestSupport(server)) {
            MuWebSocketSession session = connected.get(3, TimeUnit.SECONDS);
            if (opcode == 1) session.sendTextFragment(ByteBuffer.wrap(new byte[]{'a'}), false);
            else session.sendBinaryFragment(ByteBuffer.wrap(new byte[]{'a'}), false);
            for (boolean last : new boolean[]{false, true}) {
                assertThrows(IllegalStateException.class, () -> {
                    if (opcode == 1) session.sendBinaryFragment(ByteBuffer.wrap(new byte[]{'x'}), last);
                    else session.sendTextFragment(ByteBuffer.wrap(new byte[]{'x'}), last);
                });
            }
            session.sendPing(ByteBuffer.wrap(new byte[]{7}));
            if (opcode == 1) session.sendTextFragment(ByteBuffer.allocate(0), true);
            else session.sendBinaryFragment(ByteBuffer.allocate(0), true);
            session.sendBinary(ByteBuffer.wrap(new byte[]{'b'}));
            assertEquals(opcode, client.input.readUnsignedByte()); // Non-final initial data frame.
            assertEquals(1, client.input.readUnsignedByte());
            assertEquals('a', client.input.readUnsignedByte());
            assertArrayEquals(new byte[]{7}, client.readFrame(9));
            assertArrayEquals(new byte[0], client.readFrame(0));
            assertArrayEquals(new byte[]{'b'}, client.readFrame(2));
        }
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
            // Send all frames in one write: the server may close as soon as it reads
            // the reserved opcode, before a later client write can complete.
            ByteArrayOutputStream frames = new ByteArrayOutputStream();
            frames.write(WebSocketWireTestSupport.frame(fin, opcode, new byte[]{42}));
            if (!fin) frames.write(WebSocketWireTestSupport.frame(true, 0, new byte[]{43}));
            frames.write(WebSocketWireTestSupport.frame(true, 1, new byte[]{'o', 'k'}));
            client.output.write(frames.toByteArray());
            client.output.flush();
            byte[] close = client.readFrame(8);
            assertEquals(1002, ((close[0] & 255) << 8) | (close[1] & 255));
            try {
                assertEquals(-1, client.input.read(), "Connection must terminate after the protocol error");
            } catch (SocketException reset) {
                // Closing with unread client frames can terminate TCP with RST instead of FIN.
                // The complete 1002 WebSocket close frame was verified above in either case.
            }
            assertEquals(0, delivered.get());
        }
    }
}
