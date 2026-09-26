package io.muserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.muserver.FieldConformanceFixtures.*;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

class Http2FieldConformanceTest {
    private MuServer server;

    static Stream<Arguments> invalidFields() {
        List<Arguments> cases = new ArrayList<>();
        cases.add(Arguments.of("empty-name", "", "value", false));
        cases.add(Arguments.of("empty-content-length", "content-length", "", false));
        for (String name : List.of("X-Custom", "Content-Type", "CONTENT-LENGTH", "TE", "Connection")) {
            String value = name.equals("Content-Type") ? "text/plain" : "0";
            cases.add(Arguments.of(name, name, value, false));
            cases.add(Arguments.of(name + "-huffman", name, value, true));
        }
        // Independent full token grammar, not the production token predicate.
        for (int c = 0; c < 128; c++) {
            if (TOKEN.indexOf(c) >= 0) continue;
            for (int position = 0; position < 3; position++) {
                String name = position == 0 ? (char) c + "ab"
                    : position == 1 ? "a" + (char) c + "b" : "ab" + (char) c;
                cases.add(Arguments.of("name-" + c + "-" + position, name, "value", false));
            }
        }
        for (int c : new int[]{128, 133, 160, 255}) {
            cases.add(Arguments.of("non-ascii-name-" + c, "x" + (char) c, "value", false));
        }
        for (int c = 0; c <= 127; c++) {
            if ((c >= 32 && c != 127) || c == 9) continue;
            for (int position = 0; position < 3; position++) {
                String value = position == 0 ? (char) c + "ab"
                    : position == 1 ? "a" + (char) c + "b" : "ab" + (char) c;
                cases.add(Arguments.of("value-" + c + "-" + position, "x-value", value, false));
            }
        }
        for (String value : List.of(" value", "value ", "\tvalue", "value\t", " ", "\t", " \t ",
            " \rvalue ", "\tvalue\u0000\t")) {
            cases.add(Arguments.of("outer-whitespace-" + cases.size(), "x-value", value, false));
        }
        cases.add(Arguments.of("huffman-control", "x-value", "a\u0001b", true));
        cases.add(Arguments.of("huffman-edge-whitespace", "x-value", " value ", true));
        return cases.stream();
    }

    @Test
    void malformedInitialFieldsRejectOnlyTheirStream() {
        for (Arguments args : invalidFields().toArray(Arguments[]::new)) {
            Object[] values = args.get();
            String description = (String) values[0];
            String name = (String) values[1];
            String value = (String) values[2];
            boolean huffman = (boolean) values[3];
            assertAll(description, () -> malformedInitialFieldsRejectOnlyTheirStream(description, name, value, huffman));
        }
    }

    private void malformedInitialFieldsRejectOnlyTheirStream(String description, String name, String value,
                                                              boolean huffman) throws Exception {
        var dispatched = new CopyOnWriteArrayList<String>();
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                dispatched.add(request.uri().getPath());
                response.status(204);
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake();
            sendBlock(con, 1, true, concat(request("GET", "/rejected"),
                literal(name, value, huffman, huffman)), huffman);
            var rejected = untilReset(con, 1);
            sendBlock(con, 3, true, request("GET", "/successor"), false);
            var successor = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertAll(description,
                () -> assertInitialRejection(rejected, 1),
                () -> assertEquals(3, successor.streamId()),
                () -> assertEquals("204", successor.headers().get(":status")),
                () -> assertEquals(List.of("/successor"), dispatched));
        } finally {
            server.stop();
            server = null;
        }
    }

    static Stream<Arguments> validFields() {
        var cases = new ArrayList<Arguments>();
        cases.add(Arguments.of("token", TOKEN.toLowerCase(Locale.ROOT), "MiXeD: ,\"\\!;=()[]{}", false));
        cases.add(Arguments.of("empty", "x-empty", "", false));
        cases.add(Arguments.of("interior-whitespace", "x-space", "a  \t b", true));
        StringBuilder opaque = new StringBuilder();
        for (int c = 128; c <= 255; c++) opaque.append((char) c);
        cases.add(Arguments.of("opaque-raw", "x-opaque", opaque.toString(), false));
        cases.add(Arguments.of("opaque-huffman", "x-opaque", opaque.toString(), true));
        return cases.stream();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void malformedInitialFieldsResetWithoutWaitingForResponseDataCredit(boolean endStream) throws Exception {
        var dispatched = new CopyOnWriteArrayList<String>();
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                dispatched.add(request.uri().getPath());
                response.status(204);
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake(new Http2Settings(false, 4096, 100, 0, 16384, 32768));
            con.socket().setSoTimeout(3000);
            sendBlock(con, 1, endStream, concat(request("GET", "/rejected"),
                literal("X-Invalid", "value", false, false)), false);
            assertInitialRejection(untilReset(con, 1), 1);
            sendBlock(con, 3, true, request("GET", "/successor"), false);
            var successor = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertEquals(3, successor.streamId());
            assertEquals("204", successor.headers().get(":status"));
            assertEquals(List.of("/successor"), dispatched);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validFields")
    void validInitialAndTrailerFieldsPreserveExactValues(String description, String name, String value,
                                                         boolean huffman) throws Exception {
        var observed = new CompletableFuture<List<String>>();
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                try {
                    String initial = request.headers().get(name);
                    String body = request.readBodyAsString();
                    observed.complete(java.util.Arrays.asList(initial, body, request.trailers().get(name),
                        request.headers().get(":method"), request.headers().get(":path"),
                        request.headers().get(":scheme"), request.headers().get(":authority")));
                    response.status(204);
                } catch (Throwable error) {
                    observed.completeExceptionally(error);
                }
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake();
            sendBlock(con, 1, false, concat(request("POST", "/valid"),
                literal(name, value, false, huffman)), huffman);
            con.writeFrame(utf8DataFrame(1, false, "body")).flush();
            sendBlock(con, 1, true, literal(name, value, false, huffman), huffman);
            var response = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertEquals("204", response.headers().get(":status"));
            assertEquals(java.util.Arrays.asList(value, "body", value, null, null, null, null),
                observed.get(5, TimeUnit.SECONDS), description);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staticDynamicNameAndFullIndexRepresentationsReachApplication(boolean huffman) throws Exception {
        var observed = new CompletableFuture<List<String>>();
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                observed.complete(List.of(request.headers().get("content-type"),
                    String.join("|", request.headers().getAll("x-indexed"))));
                response.status(204);
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake();
            sendBlock(con, 1, true, concat(request("GET", "/indexed"),
                named(31, "Text/Plain; Charset=UTF-8", false, huffman),
                literal("x-indexed", "", true, huffman),
                named(62, "Second", false, huffman), indexed(62)), huffman);
            assertEquals("204", readIgnoringWindowUpdates(con, Http2HeadersFrame.class).headers().get(":status"));
            assertEquals(List.of("Text/Plain; Charset=UTF-8", "|Second|"),
                observed.get(5, TimeUnit.SECONDS));
        }
    }

    static Stream<Arguments> trailerFields() {
        var cases = new ArrayList<Arguments>();
        cases.add(Arguments.of("empty-name", "", "value", false));
        cases.add(Arguments.of("empty-content-length", "content-length", "", false));
        for (String name : List.of("X-Custom", "Content-Type", "CONTENT-LENGTH", "TE", "Connection")) {
            cases.add(Arguments.of("uppercase-" + name, name, "value", false));
        }
        cases.add(Arguments.of("uppercase-huffman", "Content-Type", "text/plain", true));
        for (String name : List.of("x y", "x/y", "x\u0000y", "x\u007fy", "x\u0085y", "x\u00ffy")) {
            cases.add(Arguments.of("invalid-name-" + cases.size(), name, "value", false));
        }
        for (int c : new int[]{0, 1, '\r', '\n', 127}) {
            for (int position = 0; position < 3; position++) {
                String value = position == 0 ? (char) c + "ab"
                    : position == 1 ? "a" + (char) c + "b" : "ab" + (char) c;
                cases.add(Arguments.of("value-" + c + "-" + position, "x-value", value, false));
            }
        }
        for (String value : List.of(" value", "value ", "\tvalue", "value\t", " ", "\t", " \t ")) {
            cases.add(Arguments.of("outer-whitespace-" + cases.size(), "x-value", value, false));
        }
        cases.add(Arguments.of("huffman-control", "x-value", "a\u0001b", true));
        cases.add(Arguments.of("huffman-edge-whitespace", "x-value", " value ", true));
        for (String name : List.of(":method", ":scheme", ":authority", ":path", ":status",
            ":extension", ":Method", "::path", "x:y",
            "content-length", "transfer-encoding", "host", "connection", "keep-alive", "proxy-connection", "te", "upgrade",
            "content-type", "content-encoding", "content-range", "authorization", "proxy-authorization")) {
            cases.add(Arguments.of("trailer-" + name, name, "value", false));
            if (name.equals(":path") || name.equals(":extension")) {
                cases.add(Arguments.of("late-pseudo-" + name, name, "value", false));
            }
        }
        return cases.stream();
    }

    @Test
    void invalidTrailersCancelReaderWithoutPublishingTerminalTrailers() {
        for (Arguments args : trailerFields().toArray(Arguments[]::new)) {
            Object[] values = args.get();
            String description = (String) values[0];
            String name = (String) values[1];
            String value = (String) values[2];
            boolean huffman = (boolean) values[3];
            assertAll(description, () -> exerciseInvalidTrailer(description, name, value, huffman, false));
        }
    }

    @Test
    void invalidTrailersAfterOutputStartedDoNotSendASecondResponse() throws Exception {
        exerciseInvalidTrailer("committed response", "Content-Type", "text/plain", false, true);
    }

    private void exerciseInvalidTrailer(String description, String name, String value,
                                        boolean huffman, boolean committed) throws Exception {
        var started = new CompletableFuture<Void>();
        var bodyOutcome = new CompletableFuture<Throwable>();
        var terminalTrailers = new CompletableFuture<Headers>();
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                if (request.uri().getPath().equals("/successor")) {
                    response.status(204);
                    return true;
                }
                try {
                    if (committed) response.sendChunk("started");
                    started.complete(null);
                    request.readBodyAsString();
                    terminalTrailers.complete(request.trailers());
                    bodyOutcome.complete(null);
                    response.status(204);
                } catch (Throwable failure) {
                    bodyOutcome.complete(failure);
                }
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake();
            sendBlock(con, 1, false, request("POST", "/trailer"), false);
            started.get(5, TimeUnit.SECONDS);
            if (committed) {
                assertEquals("200", readIgnoringWindowUpdates(con, Http2HeadersFrame.class).headers().get(":status"));
                assertEquals("started", readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8());
            }
            con.writeFrame(utf8DataFrame(1, false, "in-flight")).flush();
            byte[] trailer = literal(name, value, false, huffman);
            if (description.startsWith("late-pseudo-")) {
                trailer = concat(literal("checksum", "valid", false, false), trailer);
            }
            sendBlock(con, 1, true, trailer, huffman);
            var frames = untilReset(con, 1);
            Throwable outcome = bodyOutcome.get(5, TimeUnit.SECONDS);
            sendBlock(con, 3, true, request("GET", "/successor"), false);
            var successor = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertAll(description,
                () -> assertTrue(frames.stream().anyMatch(f -> f instanceof Http2ResetStreamFrame
                    && ((Http2ResetStreamFrame) f).streamId() == 1
                    && ((Http2ResetStreamFrame) f).errorCodeEnum() == Http2ErrorCode.PROTOCOL_ERROR),
                    "Missing trailer PROTOCOL_ERROR: " + frames),
                () -> assertFalse(frames.stream().anyMatch(f -> f instanceof Http2HeadersFrame),
                    "Trailer rejection must not send a response"),
                () -> assertFalse(frames.stream().anyMatch(f -> f instanceof Http2GoAway)),
                () -> assertNotNull(outcome, "Body reader must fail, not report successful EOF"),
                () -> assertFalse(terminalTrailers.isDone(), "Invalid terminal trailers were published"),
                () -> assertEquals(3, successor.streamId()),
                () -> assertEquals("204", successor.headers().get(":status")));
        } finally {
            server.stop();
            server = null;
        }
    }

    static Stream<Arguments> pseudoContexts() {
        var cases = new ArrayList<Arguments>();
        for (String name : List.of(":status", ":extension", "::path", ":path:extra", ":METHOD", ":Path",
            "x:embedded", ":")) {
            cases.add(Arguments.of(name, name, "value", false));
        }
        for (String name : List.of(":scheme", ":authority")) {
            cases.add(Arguments.of("duplicate-" + name, name,
                name.equals(":scheme") ? "https" : "localhost", false));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pseudoContexts")
    void requestPseudoHeaderContextIsValidated(String description, String name, String value,
                                              boolean huffman) throws Exception {
        malformedInitialFieldsRejectOnlyTheirStream(description, name, value, huffman);
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "trailers, gzip", "", " trailers"})
    void teValuesOtherThanTrailersAreRejected(String value) throws Exception {
        malformedInitialFieldsRejectOnlyTheirStream("te value", "te", value, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"trailers", "Trailers"})
    void validTeTokenIsCaseInsensitiveWithoutLowercasingItsValue(String value) throws Exception {
        var observed = new CompletableFuture<String>();
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                observed.complete(request.headers().get("te"));
                response.status(204);
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake();
            sendBlock(con, 1, true, concat(request("GET", "/te"), literal("te", value, false, true)), true);
            assertEquals("204", readIgnoringWindowUpdates(con, Http2HeadersFrame.class).headers().get(":status"));
            assertEquals(value, observed.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void endStreamOnInitialHeadersDoesNotPermitDispatchBeforeEndHeaders() throws Exception {
        var dispatched = new CopyOnWriteArrayList<String>();
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                dispatched.add(request.uri().getPath());
                response.status(204);
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake().writeRaw(headersFrame(1, true, false, request("GET", "/incomplete"))).flush();
            con.socket().setSoTimeout(100);
            assertThrows(java.net.SocketTimeoutException.class, con::readFrameHeader);
            assertTrue(dispatched.isEmpty(), "END_STREAM is not END_HEADERS");
            con.socket().setSoTimeout(3000);
            // Static name reference with a literal invalid value in the very last fragment.
            con.writeRaw(continuationFrame(1, true, named(31, " text/plain", false, false))).flush();
            var rejected = untilReset(con, 1);
            sendBlock(con, 3, true, request("GET", "/successor"), false);
            var successor = readIgnoringWindowUpdates(con, Http2HeadersFrame.class);
            assertAll(
                () -> assertInitialRejection(rejected, 1),
                () -> assertEquals(List.of("/successor"), dispatched),
                () -> assertEquals(3, successor.streamId()),
                () -> assertEquals("204", successor.headers().get(":status")));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void trailerEndStreamAndEndHeadersAreIndependent(boolean endStream) throws Exception {
        var started = new CompletableFuture<Void>();
        var finished = new CompletableFuture<String>();
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                started.complete(null);
                try {
                    request.readBodyAsString();
                    finished.complete(request.trailers().get("checksum"));
                    response.status(204);
                } catch (Throwable failure) {
                    finished.completeExceptionally(failure);
                }
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake();
            sendBlock(con, 1, false, request("POST", "/flags"), false);
            started.get(5, TimeUnit.SECONDS);
            con.writeRaw(headersFrame(1, endStream, false, literal("checksum", "Exact", false, false)))
                .flush();
            con.socket().setSoTimeout(100);
            assertThrows(java.net.SocketTimeoutException.class, con::readFrameHeader);
            assertFalse(finished.isDone(), "END_STREAM must not publish an incomplete field block");
            con.socket().setSoTimeout(3000);
            con.writeRaw(continuationFrame(1, true, new byte[0])).flush();
            if (endStream) {
                assertEquals("204", readIgnoringWindowUpdates(con, Http2HeadersFrame.class).headers().get(":status"));
                assertEquals("Exact", finished.get(5, TimeUnit.SECONDS));
            } else {
                var reset = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
                assertEquals(1, reset.streamId());
                assertEquals(Http2ErrorCode.PROTOCOL_ERROR, reset.errorCodeEnum());
                assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> finished.get(5, TimeUnit.SECONDS));
            }
        }
    }

    private byte[] request(String method, String path) throws IOException {
        return concat(named(2, method, false, false), indexed(7),
            named(1, "localhost:" + server.uri().getPort(), false, false), named(4, path, false, false));
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }
}
