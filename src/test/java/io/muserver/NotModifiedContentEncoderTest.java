package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import scaffolding.ServerUtils;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static io.muserver.RFCTestUtils.*;
import static io.muserver.ResponseFramingTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class NotModifiedContentEncoderTest {
    static Stream<Arguments> cases() {
        return Stream.of("HTTP/1.0", "HTTP/1.1", "h2").flatMap(protocol ->
            Arrays.stream(WriteStyle.values()).filter(style -> style != WriteStyle.NONE).flatMap(style ->
                Stream.of("gzip", "identity", "already-encoded").map(coding -> Arguments.of(protocol, style, coding))));
    }

    @ParameterizedTest
    @MethodSource("cases")
    void notModifiedNegotiatesLikeOkWithoutSendingContent(String protocol, WriteStyle style, String coding) throws Exception {
        var prepared304 = new AtomicInteger();
        var wrapped304 = new AtomicInteger();
        var closed304 = new AtomicInteger();
        var completed304 = new CompletableFuture<ResponseInfo>();
        var gzip = GZIPEncoderBuilder.gzipEncoder().withMinGzipSize(0).withMimeTypesToGzip(Set.of("text/plain")).build();
        var encoder = new ContentEncoder() {
            @Override public String contentCoding() { return gzip.contentCoding(); }
            @Override public boolean prepare(MuRequest request, MuResponse response) {
                if (response.status().code() == 304) prepared304.incrementAndGet();
                return gzip.prepare(request, response);
            }
            @Override public OutputStream wrapStream(MuRequest request, MuResponse response, OutputStream stream) throws IOException {
                OutputStream encoded = gzip.wrapStream(request, response, stream);
                if (response.status().code() != 304) return encoded;
                wrapped304.incrementAndGet();
                return new FilterOutputStream(encoded) {
                    private boolean closed;
                    @Override public void close() throws IOException {
                        if (!closed) {
                            closed = true;
                            try { super.close(); } finally { closed304.incrementAndGet(); }
                        }
                    }
                };
            }
        };
        try (var server = ServerUtils.httpsServerForTest(protocol.equals("h2") ? "h2" : "http")
            .withContentEncoders(List.of(encoder))
            .addResponseCompleteListener(info -> {
                if (info.response().status().code() == 304) completed304.complete(info);
            })
            .addHandler(Method.GET, "/hello", (request, response, params) -> {
                response.status(request.headers().contains("if-none-match") ? 304 : 200);
                response.contentType("text/plain");
                response.headers().set("etag", "\"version1\"");
                response.headers().set("vary", "accept-language");
                if (coding.equals("already-encoded")) response.headers().set("content-encoding", "test");
                writeBody(style, request, response);
            }).start()) {
            FieldBlock ok = exchange(server, protocol, coding, false);
            FieldBlock notModified = exchange(server, protocol, coding, true);
            assertTrue(completed304.get(2, TimeUnit.SECONDS).completedSuccessfully());
            assertAll("304 must preserve the selected representation metadata",
                () -> assertEquals(ok.get("vary"), notModified.get("vary")),
                () -> assertEquals(ok.get("content-encoding"), notModified.get("content-encoding")),
                () -> assertEquals(ok.get("content-length"), notModified.get("content-length")));
            assertNull(notModified.get("transfer-encoding"));
            assertEquals("\"version1\"", notModified.get("etag"));
            assertEquals(1, prepared304.get());
            int expectedWraps = coding.equals("gzip") ? 1 : 0;
            assertEquals(expectedWraps, wrapped304.get());
            assertEquals(expectedWraps, closed304.get());
            if (coding.equals("gzip")) {
                assertEquals("gzip", notModified.get("content-encoding"));
                assertNull(notModified.get("content-length"), "Do not advertise the unencoded length for gzip");
            }
        }
    }

    private static FieldBlock exchange(MuServer server, String protocol, String coding, boolean conditional) throws Exception {
        int expectedStatus = conditional ? 304 : 200;
        if (protocol.equals("h2")) {
            try (var client = new H2Client(); var con = client.connect(server)) {
                con.socket().setSoTimeout(2000);
                var headers = getHelloHeaders(server.uri().getPort());
                headers.set("accept-encoding", coding.equals("identity") ? "identity" : "gzip");
                if (conditional) headers.set("if-none-match", "\"version1\"");
                con.handshake().writeFrame(new Http2HeadersFrame(1, true, headers)).flush();
                var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
                assertEquals(Integer.toString(expectedStatus), response.headers().get(":status"));
                if (conditional) {
                    assertTrue(response.endStream(), "304 must end without gzip DATA frames");
                } else if (!response.endStream()) {
                    Http2DataFrame frame;
                    do { frame = readIgnoringWindowUpdates(con, Http2DataFrame.class); } while (!frame.endStream());
                }
                return response.headers();
            }
        }
        try (var socket = new Socket("localhost", server.uri().getPort())) {
            socket.setSoTimeout(2000);
            String request = "GET /hello " + protocol + "\r\nHost: localhost\r\nConnection: close\r\n"
                + "Accept-Encoding: " + (coding.equals("identity") ? "identity" : "gzip") + "\r\n"
                + (conditional ? "If-None-Match: \"version1\"\r\n" : "") + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            String wire = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertTrue(wire.startsWith("HTTP/1.1 " + expectedStatus), wire);
            int headerEnd = wire.indexOf("\r\n\r\n");
            assertTrue(headerEnd > 0, wire);
            if (conditional) assertEquals("", wire.substring(headerEnd + 4), "304 sent encoder output");
            var headers = new FieldBlock();
            for (String line : wire.substring(0, headerEnd).split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.add(line.substring(0, colon), line.substring(colon + 1).trim());
            }
            return headers;
        }
    }
}
