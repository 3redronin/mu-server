package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import scaffolding.ServerUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.muserver.RFCTestUtils.*;
import static io.muserver.ResponseFramingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class HeadContentEncoderTest {
    static Stream<Arguments> cases() {
        return Stream.of("http", "h2").flatMap(protocol -> Arrays.stream(WriteStyle.values()).flatMap(style ->
            Stream.of(false, true).map(accept -> Arguments.of(protocol, style, accept))));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void preparedHeadEncoderIsWrappedAndClosedWithoutSendingContent(String protocol, WriteStyle style, boolean accept) throws Exception {
        var encoder = new StatefulEncoder(accept);
        var completed = new CompletableFuture<ResponseInfo>();
        try (var server = ServerUtils.httpsServerForTest(protocol)
            .withContentEncoders(List.of(encoder))
            .addResponseCompleteListener(info -> {
                if (info.request().method().isHead()) completed.complete(info);
            })
            .addHandler(Method.HEAD, "/hello", (request, response, params) -> writeBody(style, request, response))
            .addHandler(Method.GET, "/hello", (request, response, params) -> response.write("next"))
            .start()) {
            if (protocol.equals("http")) {
                try (var socket = new Socket("localhost", server.uri().getPort())) {
                    socket.setSoTimeout(2000);
                    socket.getOutputStream().write(("HEAD /hello HTTP/1.1\r\nHost: localhost\r\n\r\n"
                        + "GET /hello HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                    String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                    assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
                    assertTrue(wire.substring(wire.indexOf("\r\n\r\n") + 4).startsWith("HTTP/1.1 200"), wire);
                    assertTrue(wire.endsWith("next"), wire);
                }
            } else {
                try (var client = new H2Client(); var con = client.connect(server)) {
                    con.socket().setSoTimeout(2000);
                    var headers = getHelloHeaders(server.uri().getPort());
                    headers.set(":method", "HEAD");
                    con.handshake().writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
                    var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
                    assertEquals("200", response.headers().get(":status"));
                    assertTrue(response.endStream(), "HEAD must end without encoder DATA frames");
                    con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(server.uri().getPort()))).flush();
                    assertEquals("200", readIgnoringWindowUpdates(con, Http2HeadersFrame.class).headers().get(":status"));
                    StringBuilder body = new StringBuilder();
                    Http2DataFrame frame;
                    do {
                        frame = readIgnoringWindowUpdates(con, Http2DataFrame.class);
                        body.append(frame.toUTF8());
                    } while (!frame.endStream());
                    assertEquals("next", body.toString());
                }
            }
            assertTrue(completed.get(2, TimeUnit.SECONDS).completedSuccessfully());
            assertEquals(style == WriteStyle.NONE ? 0 : 1, encoder.prepared.get());
            int expectedWraps = accept && style != WriteStyle.NONE ? 1 : 0;
            assertEquals(expectedWraps, encoder.wrapped.get(), "Successful preparation must be followed by wrapping");
            assertEquals(expectedWraps, encoder.released.get(), "Encoder resources must be released on completion");
            assertFalse(encoder.resourceHeld.get());
            int expectedBytes = expectedWraps == 1 && style != WriteStyle.EMPTY_STREAM && style != WriteStyle.EMPTY_WRITER ? 5 : 0;
            assertEquals(expectedBytes, encoder.bodyBytes.get());
        }
    }

    private static final class StatefulEncoder implements ContentEncoder {
        final boolean accept;
        final AtomicInteger prepared = new AtomicInteger();
        final AtomicInteger wrapped = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();
        final AtomicInteger bodyBytes = new AtomicInteger();
        final AtomicBoolean resourceHeld = new AtomicBoolean();

        StatefulEncoder(boolean accept) { this.accept = accept; }

        @Override public String contentCoding() { return "test"; }

        @Override
        public boolean prepare(MuRequest request, MuResponse response) {
            if (!request.method().isHead()) return false;
            prepared.incrementAndGet();
            if (accept) {
                resourceHeld.set(true);
                response.headers().set("content-encoding", contentCoding());
                response.headers().remove("content-length");
            }
            return accept;
        }

        @Override
        public OutputStream wrapStream(MuRequest request, MuResponse response, OutputStream stream) throws IOException {
            wrapped.incrementAndGet();
            assertTrue(resourceHeld.get());
            stream.write("prefix".getBytes(StandardCharsets.US_ASCII));
            return new OutputStream() {
                private boolean closed;
                @Override public void write(int b) throws IOException {
                    bodyBytes.incrementAndGet();
                    stream.write(b);
                }
                @Override public void flush() throws IOException { stream.flush(); }
                @Override public void close() throws IOException {
                    if (!closed) {
                        closed = true;
                        try {
                            stream.write("suffix".getBytes(StandardCharsets.US_ASCII));
                            stream.close();
                        } finally {
                            resourceHeld.set(false);
                            released.incrementAndGet();
                        }
                    }
                }
            };
        }
    }
}
