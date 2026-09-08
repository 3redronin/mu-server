package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import scaffolding.ServerUtils;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.muserver.RFCTestUtils.*;
import static io.muserver.ResponseFramingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class BodylessResponseTest {
    static Stream<Arguments> http1Cases() {
        return Stream.of("HTTP/1.0", "HTTP/1.1").flatMap(version ->
            Stream.of(204, 205, 304).flatMap(status -> Arrays.stream(WriteStyle.values()).flatMap(style ->
                Stream.of(false, true).map(gzip -> Arguments.of(version, status, style, gzip)))));
    }

    static Stream<Arguments> http2Cases() {
        return Stream.of(204, 205, 304).flatMap(status -> Arrays.stream(WriteStyle.values()).flatMap(style ->
            Stream.of(false, true).map(gzip -> Arguments.of(status, style, gzip))));
    }

    private static void respond(int status, WriteStyle style, MuRequest request, MuResponse response) throws Exception {
        response.status(status);
        response.contentType("text/plain");
        // Existing representation metadata must be preserved for 304, removed for
        // 204, and replaced by zero-length framing for 205.
        if (style == WriteStyle.NONE || style == WriteStyle.EMPTY_STREAM || style == WriteStyle.EMPTY_WRITER) {
            response.headers().set("content-length", "7");
        }
        writeBody(style, request, response);
    }

    @ParameterizedTest
    @MethodSource("http1Cases")
    void http1BodylessResponseLeavesNextResponseIntact(String version, int status, WriteStyle style, boolean gzip) throws Exception {
        var completed = new CompletableFuture<ResponseInfo>();
        try (var server = ServerUtils.httpsServerForTest("http")
            .addResponseCompleteListener(completed::complete)
            .addHandler(Method.GET, "/", (request, response, params) -> {
                response.headers().set("connection", "keep-alive");
                respond(status, style, request, response);
            })
            .addHandler(Method.GET, "/next", (request, response, params) -> response.write("next"))
            .start();
             var socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(2000);
            String requests = "GET / " + version + "\r\nHost: localhost\r\nConnection: keep-alive\r\n"
                + (gzip ? "Accept-Encoding: gzip\r\n" : "") + "\r\n"
                + "GET /next " + version + "\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(requests.getBytes(StandardCharsets.US_ASCII));
            String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(wire.startsWith("HTTP/1.1 " + status), wire);
            int headerEnd = wire.indexOf("\r\n\r\n");
            String headers = wire.substring(0, headerEnd).toLowerCase(Locale.ROOT);
            String afterHeaders = wire.substring(headerEnd + 4);
            assertTrue(afterHeaders.startsWith("HTTP/1.1 200"), "Unexpected body bytes or lost next response: " + wire);
            assertTrue(afterHeaders.endsWith("next"), wire);
            assertFalse(headers.contains("transfer-encoding:"), headers);
            if (status == 204) assertFalse(headers.contains("content-length:"), headers);
            if (status == 205) assertTrue(headers.contains("content-length: 0"), headers);
            if (status == 304 && (style == WriteStyle.NONE || style == WriteStyle.EMPTY_STREAM || style == WriteStyle.EMPTY_WRITER)) {
                assertTrue(headers.contains("content-length: 7"), headers);
            }
            assertTrue(completed.get(2, TimeUnit.SECONDS).completedSuccessfully());
        }
    }

    @ParameterizedTest
    @MethodSource("http2Cases")
    void http2BodylessResponseEndsStreamWithoutContent(int status, WriteStyle style, boolean gzip) throws Exception {
        var completed = new CompletableFuture<ResponseInfo>();
        try (var server = ServerUtils.httpsServerForTest("h2")
            .addResponseCompleteListener(completed::complete)
            .addHandler(Method.GET, "/hello", (request, response, params) -> respond(status, style, request, response))
            .start();
             var client = new H2Client();
             var con = client.connect(server)) {
            con.socket().setSoTimeout(2000);
            var headers = getHelloHeaders(server.uri().getPort());
            if (gzip) headers.set("accept-encoding", "gzip");
            con.handshake().writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertEquals(Integer.toString(status), response.headers().get(":status"));
            int payload = 0;
            if (!response.endStream()) {
                Http2DataFrame frame;
                do {
                    frame = readIgnoringWindowUpdates(con, Http2DataFrame.class);
                    payload += frame.payloadLength();
                } while (!frame.endStream());
            }
            assertEquals(0, payload);
            assertNull(response.headers().get("transfer-encoding"));
            if (status == 204) assertNull(response.headers().get("content-length"));
            if (status == 205) assertEquals("0", response.headers().get("content-length"));
            if (status == 304 && (style == WriteStyle.NONE || style == WriteStyle.EMPTY_STREAM || style == WriteStyle.EMPTY_WRITER)) {
                assertEquals("7", response.headers().get("content-length"));
            }
            assertTrue(completed.get(2, TimeUnit.SECONDS).completedSuccessfully());
        }
    }
}
