package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import scaffolding.ServerUtils;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.muserver.RFCTestUtils.*;
import static io.muserver.ResponseFramingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class HeadResponseFramingTest {
    static Stream<Arguments> writes() {
        return Arrays.stream(WriteStyle.values()).flatMap(style -> Stream.of(
            Arguments.of(style, false), Arguments.of(style, true)));
    }

    @ParameterizedTest
    @MethodSource("writes")
    void http1HeadHasNoDataAndConnectionRemainsUsable(WriteStyle style, boolean gzip) throws Exception {
        var completed = new CompletableFuture<ResponseInfo>();
        try (var server = ServerUtils.httpsServerForTest("http")
            .addResponseCompleteListener(completed::complete)
            .addHandler(Method.HEAD, "/", (request, response, params) -> {
                response.contentType("text/plain");
                if (style == WriteStyle.NONE || style == WriteStyle.EMPTY_STREAM || style == WriteStyle.EMPTY_WRITER) {
                    response.headers().set("content-length", "5");
                }
                writeBody(style, request, response);
            })
            .addHandler(Method.GET, "/", (request, response, params) -> response.write("next"))
            .start();
             var socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(2000);
            String requests = "HEAD / HTTP/1.1\r\nHost: localhost\r\n"
                + (gzip ? "Accept-Encoding: gzip\r\n" : "") + "\r\n"
                + "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(requests.getBytes(StandardCharsets.US_ASCII));
            String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
            assertTrue(wire.startsWith("HTTP/1.1 200"), wire);
            assertTrue(wire.substring(wire.indexOf("\r\n\r\n") + 4).startsWith("HTTP/1.1 200"), wire);
            assertTrue(wire.endsWith("next"), wire);
            assertTrue(completed.get(2, TimeUnit.SECONDS).completedSuccessfully());
        }
    }

    @ParameterizedTest
    @MethodSource("writes")
    void http2HeadHasNoDataAndConnectionRemainsUsable(WriteStyle style, boolean gzip) throws Exception {
        var completed = new CompletableFuture<ResponseInfo>();
        try (var server = ServerUtils.httpsServerForTest("h2")
            .addResponseCompleteListener(completed::complete)
            .addHandler(Method.HEAD, "/hello", (request, response, params) -> {
                response.contentType("text/plain");
                if (style == WriteStyle.NONE || style == WriteStyle.EMPTY_STREAM || style == WriteStyle.EMPTY_WRITER) {
                    response.headers().set("content-length", "5");
                }
                writeBody(style, request, response);
            })
            .addHandler(Method.GET, "/hello", (request, response, params) -> response.write("next"))
            .start();
             var client = new H2Client();
             var con = client.connect(server)) {
            con.socket().setSoTimeout(2000);
            var headers = getHelloHeaders(server.uri().getPort());
            headers.set(":method", "HEAD");
            if (gzip) headers.set("accept-encoding", "gzip");
            con.handshake().writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertEquals("200", response.headers().get(":status"));
            if (!gzip && (style == WriteStyle.WRITE || style == WriteStyle.NONE
                || style == WriteStyle.EMPTY_STREAM || style == WriteStyle.EMPTY_WRITER)) {
                assertEquals("5", response.headers().get("content-length"));
            }
            int bodyBytes = 0;
            if (!response.endStream()) {
                Http2DataFrame frame;
                do {
                    frame = readIgnoringWindowUpdates(con, Http2DataFrame.class);
                    bodyBytes += frame.payloadLength();
                } while (!frame.endStream());
            }
            assertEquals(0, bodyBytes, "HEAD sent DATA payload bytes");
            assertTrue(completed.get(2, TimeUnit.SECONDS).completedSuccessfully());
            con.writeFrame(new Http2HeadersFrame(3, true, getHelloHeaders(server.uri().getPort()))).flush();
            assertEquals("200", readIgnoringWindowUpdates(con, Http2HeadersFrame.class).headers().get(":status"));
            var body = new StringBuilder();
            Http2DataFrame frame;
            do {
                frame = readIgnoringWindowUpdates(con, Http2DataFrame.class);
                body.append(frame.toUTF8());
            } while (!frame.endStream());
            assertEquals("next", body.toString());
        }
    }
}
