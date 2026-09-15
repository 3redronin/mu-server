package io.muserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.MuAssert.assertEventually;

@Timeout(10)
class WebsocketUtf8Test {
    private MuServer server;
    private final AtomicInteger fragments = new AtomicInteger();

    @AfterEach void stop() { if (server != null) server.stop(); }

    private WebSocketWireTestSupport connect() throws Exception { return connect(false); }

    private WebSocketWireTestSupport connect(boolean aggregate) throws Exception {
        server = httpServer().addHandler(webSocketHandler((request, headers) -> new SimpleWebSocket() {
            @Override public void onText(String text) throws Exception { session().sendText(text); }
            @Override public void onBinary(ByteBuffer data) throws Exception { session().sendBinary(data); }
            // Deliberately does not decode: the transport must validate even for fragment handlers.
            @Override public void onTextFragment(ByteBuffer data, boolean last) throws Exception {
                fragments.incrementAndGet();
                if (aggregate) super.onTextFragment(data, last);
            }
        }).withMaxFramePayloadLength(1024 * 1024).withPingInterval(0, TimeUnit.MILLISECONDS)).start();
        return new WebSocketWireTestSupport(server);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAnInvalidPrefixBeforeTheRestOfTheFrameArrives(boolean continuation) throws Exception {
        try (WebSocketWireTestSupport client = connect()) {
            if (continuation) {
                client.send(false, 1, new byte[]{'a', (byte) 0xf4});
                assertEventually(fragments::get, equalTo(1));
            }
            byte[] payload = continuation ? new byte[]{(byte) 0x90, (byte) 0x80, (byte) 0x80, 'b'}
                : new byte[]{(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80, 'b'};
            byte[] frame = WebSocketWireTestSupport.frame(true, continuation ? 0 : 1, payload);
            // Supply only the bytes that make the code point exceed U+10FFFF, withholding the tail.
            client.output.write(frame, 0, 6 + (continuation ? 1 : 2));
            client.output.flush();
            assertInvalidTextClose(client);
            assertEquals(continuation ? 1 : 0, fragments.get());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsInvalidNonFinalFragmentsBeforeCallingTheHandler(boolean continuation) throws Exception {
        try (WebSocketWireTestSupport client = connect()) {
            if (continuation) {
                client.send(false, 1, new byte[]{(byte) 0xf4});
                assertEventually(fragments::get, equalTo(1));
            }
            client.send(false, continuation ? 0 : 1, continuation
                ? new byte[]{(byte) 0x90} : new byte[]{(byte) 0xf4, (byte) 0x90});
            assertInvalidTextClose(client);
            assertEquals(continuation ? 1 : 0, fragments.get());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0x80, 0x7ff, 0x800, 0xd7ff, 0xe000, 0xffff, 0x10000, 0x10ffff})
    void validCharactersCanSpanFramesWithInterleavedControlPayloads(int codePoint) throws Exception {
        byte[] text = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
        try (WebSocketWireTestSupport client = connect(true)) {
            for (int i = 0; i < text.length; i++) {
                client.send(false, i == 0 ? 1 : 0, new byte[]{text[i]});
                // These bytes are invalid UTF-8 but are legal in control and binary payloads.
                client.send(true, 9, new byte[]{(byte) 0xff, (byte) 0xc0});
                assertArrayEquals(new byte[]{(byte) 0xff, (byte) 0xc0}, client.readFrame(10));
            }
            client.send(true, 0, new byte[0]);
            assertArrayEquals(text, client.readFrame(1));
            client.send(true, 2, new byte[]{(byte) 0xff});
            assertArrayEquals(new byte[]{(byte) 0xff}, client.readFrame(2));
            client.send(true, 1, new byte[]{'o', 'k'});
            assertArrayEquals(new byte[]{'o', 'k'}, client.readFrame(1));
        }
    }

    @Test
    void incompleteCharacterIsRejectedAtAnEmptyFinalContinuation() throws Exception {
        try (WebSocketWireTestSupport client = connect()) {
            client.send(false, 1, new byte[]{(byte) 0xf0, (byte) 0x90});
            assertEventually(fragments::get, equalTo(1));
            client.send(true, 0, new byte[0]);
            assertInvalidTextClose(client);
            assertEquals(1, fragments.get());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 8191, 8192, 65535})
    void validCharactersSurviveSocketAndBufferBoundaries(int prefixLength) throws Exception {
        byte[] text = ("a".repeat(prefixLength) + "\uD83D\uDC4B" + "end").getBytes(StandardCharsets.UTF_8);
        try (WebSocketWireTestSupport client = connect(true)) {
            byte[] frame = WebSocketWireTestSupport.frame(true, 1, text);
            int split = frame.length - text.length + prefixLength + 1;
            client.output.write(frame, 0, split);
            client.output.flush();
            assertEventually(() -> server.stats().bytesRead(), equalTo((long) client.handshakeBytes + split));
            client.output.write(frame, split, frame.length - split);
            client.output.flush();
            assertArrayEquals(text, client.readFrame(1));
        }
    }

    private static void assertInvalidTextClose(WebSocketWireTestSupport client) throws Exception {
        byte[] close = client.readFrame(8);
        assertTrue(close.length >= 2);
        assertEquals(1007, ((close[0] & 255) << 8) | (close[1] & 255));
    }
}
