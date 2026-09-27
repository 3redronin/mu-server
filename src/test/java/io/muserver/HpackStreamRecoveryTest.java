package io.muserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.muserver.FieldConformanceFixtures.*;
import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.RFCTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

class HpackStreamRecoveryTest {
    private MuServer server;

    static Stream<Arguments> permutations() {
        return Stream.of(false, true).flatMap(huffman ->
            Stream.of(false, true).flatMap(fragmented ->
                Stream.of(false, true).map(invalidFirst -> Arguments.of(huffman, fragmented, invalidFirst))));
    }

    @ParameterizedTest(name = "Huffman={0}, fragmented={1}, invalidFirst={2}")
    @MethodSource("permutations")
    void rejectedStreamsStillIndexEveryFieldAndKeepOtherBodiesMoving(boolean huffman, boolean fragmented,
                                                                    boolean invalidFirst) throws Exception {
        var activeStarted = new CompletableFuture<Void>();
        var activeBody = new CompletableFuture<String>();
        var seen = new ConcurrentHashMap<String, String>();
        var dispatches = new CopyOnWriteArrayList<String>();
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                String path = request.uri().getPath();
                dispatches.add(path);
                if (path.equals("/active")) {
                    activeStarted.complete(null);
                    try {
                        activeBody.complete(request.readBodyAsString());
                    } catch (Throwable error) {
                        activeBody.completeExceptionally(error);
                    }
                } else {
                    seen.put(path, request.headers().get("x-sentinel", "") + "|" + request.headers().get("x-bad", ""));
                }
                response.status(204);
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake();
            con.socket().setSoTimeout(3000);
            sendBlock(con, 1, false, request("/active", "POST"), false);
            activeStarted.get(5, TimeUnit.SECONDS);
            con.writeFrame(utf8DataFrame(1, false, "before-")).flush();
            byte[] bad = literal("x-bad", "a\u0000b", true, huffman);
            byte[] sentinel = literal("x-sentinel", "Sentinel-" + "z".repeat(128), true, huffman);
            sendBlock(con, 3, false, concat(request("/bad", "POST"),
                invalidFirst ? concat(bad, sentinel) : concat(sentinel, bad)), fragmented);
            con.writeFrame(utf8DataFrame(3, true, "already-in-flight")).flush();
            var rejected = untilReset(con, 3);
            int goodIndex = invalidFirst ? 62 : 63;
            int badIndex = invalidFirst ? 63 : 62;
            sendBlock(con, 5, true, concat(request("/reuse", "GET"), indexed(goodIndex)), fragmented);
            var reused = terminal(con, 5);
            // Validation is per field instance, not metadata cached on an indexed name.
            sendBlock(con, 7, true, concat(request("/repaired", "GET"),
                named(badIndex, "Valid-New-Value", false, huffman)), fragmented);
            var repaired = terminal(con, 7);
            var repeatErrors = new ArrayList<List<LogicalHttp2Frame>>();
            for (int stream : new int[]{9, 11, 13}) {
                sendBlock(con, stream, true, concat(request("/bad-again-" + stream, "GET"),
                    indexed(badIndex)), fragmented);
                repeatErrors.add(untilReset(con, stream));
            }
            sendBlock(con, 15, true, concat(request("/last", "GET"), indexed(goodIndex)), false);
            var last = terminal(con, 15);
            con.writeFrame(utf8DataFrame(1, true, "after")).flush();
            var active = terminal(con, 1);
            assertAll(
                () -> assertInitialRejection(rejected, 3),
                () -> assertInitialRejection(repeatErrors.get(0), 9),
                () -> assertInitialRejection(repeatErrors.get(1), 11),
                () -> assertInitialRejection(repeatErrors.get(2), 13),
                () -> successful(reused, 5, "204"),
                () -> successful(repaired, 7, "204"),
                () -> successful(last, 15, "204"),
                () -> successful(active, 1, "204"),
                () -> assertEquals("Sentinel-" + "z".repeat(128) + "|", seen.get("/reuse")),
                () -> assertEquals("|Valid-New-Value", seen.get("/repaired")),
                () -> assertEquals(seen.get("/reuse"), seen.get("/last")),
                () -> assertEquals("before-after", activeBody.get(5, TimeUnit.SECONDS)),
                () -> assertEquals(List.of("/active", "/reuse", "/repaired", "/last"), dispatches));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedIndexedNamesAreNotCachedAsValidated(boolean fragmented) throws Exception {
        var dispatched = new CopyOnWriteArrayList<String>();
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                dispatched.add(request.uri().getPath());
                response.status(204);
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake();
            sendBlock(con, 1, true, concat(request("/bad1", "GET"),
                literal("X-Custom", "value", true, false)), fragmented);
            var first = untilReset(con, 1);
            sendBlock(con, 3, true, concat(request("/bad2", "GET"), indexed(62)), fragmented);
            var full = untilReset(con, 3);
            sendBlock(con, 5, true, concat(request("/bad3", "GET"), named(62, "new", false, false)), fragmented);
            var name = untilReset(con, 5);
            sendBlock(con, 7, true, request("/valid", "GET"), false);
            var valid = terminal(con, 7);
            assertAll(() -> assertInitialRejection(first, 1), () -> assertInitialRejection(full, 3),
                () -> assertInitialRejection(name, 5), () -> successful(valid, 7, "204"),
                () -> assertEquals(List.of("/valid"), dispatched));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidTrailerBlockIndexesSentinelAndCancelsCommittedBodyReader(boolean huffman) throws Exception {
        var started = new CompletableFuture<Void>();
        var outcome = new CompletableFuture<Throwable>();
        var published = new CompletableFuture<Headers>();
        var reused = new CompletableFuture<String>();
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                if (request.uri().getPath().equals("/successor")) {
                    reused.complete(request.headers().get("x-sentinel"));
                    response.status(204);
                } else {
                    try {
                        response.sendChunk("started");
                        started.complete(null);
                        request.readBodyAsString();
                        published.complete(request.trailers());
                        outcome.complete(null);
                    } catch (Throwable error) {
                        outcome.complete(error);
                    }
                }
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake();
            sendBlock(con, 1, false, request("/trailer", "POST"), false);
            started.get(5, TimeUnit.SECONDS);
            assertEquals("200", readIgnoringWindowUpdates(con, Http2HeadersFrame.class).headers().get(":status"));
            assertEquals("started", readIgnoringWindowUpdates(con, Http2DataFrame.class).toUTF8());
            con.writeFrame(utf8DataFrame(1, false, "in-flight")).flush();
            sendBlock(con, 1, true, concat(literal("x-bad", "a\u0000b", true, huffman),
                literal("x-sentinel", "Trailer-Sentinel", true, huffman)), true);
            var rejection = untilReset(con, 1);
            sendBlock(con, 3, true, concat(request("/successor", "GET"), indexed(62)), false);
            var successor = terminal(con, 3);
            assertAll(
                () -> assertTrue(rejection.stream().anyMatch(f -> f instanceof Http2ResetStreamFrame
                    && ((Http2ResetStreamFrame) f).streamId() == 1
                    && ((Http2ResetStreamFrame) f).errorCodeEnum() == Http2ErrorCode.PROTOCOL_ERROR)),
                () -> assertFalse(rejection.stream().anyMatch(f -> f instanceof Http2HeadersFrame
                    || f instanceof Http2GoAway), "No second response or connection error"),
                () -> assertNotNull(outcome.get(5, TimeUnit.SECONDS)),
                () -> assertFalse(published.isDone(), "Invalid trailers cannot be terminally published"),
                () -> successful(successor, 3, "204"),
                () -> assertEquals("Trailer-Sentinel", reused.get(5, TimeUnit.SECONDS)));
        }
    }

    static Stream<Arguments> corruptBlocks() {
        return Stream.of("80", "ff00", "3fe21f", "00017881ff", "00017805", "400178017920")
            .flatMap(hex -> Stream.of(false, true).map(fragmented -> Arguments.of(hex, fragmented)));
    }

    @ParameterizedTest(name = "suffix={0}, fragmented={1}")
    @MethodSource("corruptBlocks")
    void compressionFailureTakesPrecedenceOverEarlierHttpError(String suffix, boolean fragmented) throws Exception {
        var dispatched = new CopyOnWriteArrayList<String>();
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                dispatched.add(request.uri().getPath());
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake();
            con.socket().setSoTimeout(3000);
            byte[] invalid = concat(request("/invalid", "GET"),
                literal("X-Custom", "value", true, false),
                FieldBlockEncoderTest.hexToByteArray(suffix));
            if (fragmented) {
                int split = invalid.length / 2;
                con.writeRaw(headersFrame(1, true, false, Arrays.copyOf(invalid, split)))
                    .writeRaw(continuationFrame(1, true, Arrays.copyOfRange(invalid, split, invalid.length)));
            } else {
                con.writeRaw(headersFrame(1, true, true, invalid));
            }
            con.writeRaw(headersFrame(3, true, true, request("/must-not-dispatch", "GET"))).flush();
            var frames = terminal(con, 1);
            assertAll(() -> compressionError(frames),
                () -> assertTrue(dispatched.isEmpty()),
                () -> assertConnectionClosed(con));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void invalidContinuationScopeIsProtocolNotCompressionError(boolean wrongStream) throws Exception {
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled()).start();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake().writeRaw(headersFrame(1, true, false, new byte[]{0})).flush();
            if (wrongStream) con.writeRaw(continuationFrame(3, true, new byte[0])).flush();
            else con.writeFrame(new Http2Ping(false, new byte[8])).flush();
            var frame = readIgnoringWindowUpdates(con, Http2GoAway.class);
            assertEquals(Http2ErrorCode.PROTOCOL_ERROR, frame.errorCodeEnum());
            assertConnectionClosed(con);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"headers", "uri"})
    void retainedConnectionsDecodeSentinelAfterResourceLimitRejection(String limit) throws Exception {
        var reused = new CompletableFuture<String>();
        var dispatched = new CopyOnWriteArrayList<String>();
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .withMaxHeadersSize(limit.equals("headers") ? 512 : 8192)
            .withMaxUrlSize(limit.equals("uri") ? 64 : 8192)
            .addHandler((request, response) -> {
                dispatched.add(request.uri().getPath());
                reused.complete(request.headers().get("x-sentinel"));
                response.status(204);
                return true;
            }).start();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake();
            byte[] overLimit = request(limit.equals("uri") ? "/" + "u".repeat(128) : "/oversize", "GET");
            if (limit.equals("headers")) overLimit = concat(overLimit, literal("x-large", "v".repeat(600), false, false));
            sendBlock(con, 1, true, concat(overLimit, literal("x-sentinel", "After-Limit", true, false)), false);
            var rejection = terminal(con, 1);
            sendBlock(con, 3, true, concat(request("/successor", "GET"), indexed(62)), false);
            var success = terminal(con, 3);
            assertAll(
                () -> assertTrue(rejection.stream().anyMatch(f -> f instanceof Http2HeadersFrame
                    && ((Http2HeadersFrame) f).headers().get(":status").equals(limit.equals("uri") ? "414" : "431"))),
                () -> assertFalse(rejection.stream().anyMatch(f -> f instanceof Http2GoAway)),
                () -> successful(success, 3, "204"),
                () -> assertEquals("After-Limit", reused.get(5, TimeUnit.SECONDS)),
                () -> assertEquals(List.of("/successor"), dispatched));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finalGoAwayDiscardStillDecodesForLiveLowerStreamTrailers(boolean fragmented) throws Exception {
        goAwayDiscard(fragmented, false);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void corruptDiscardedBlockAfterFinalGoAwayIsACompressionError(boolean fragmented) throws Exception {
        goAwayDiscard(fragmented, true);
    }

    private void goAwayDiscard(boolean fragmented, boolean corrupt) throws Exception {
        var started = new CompletableFuture<Void>();
        var completed = new CompletableFuture<String>();
        var dispatches = new CopyOnWriteArrayList<String>();
        server = httpsServer().withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                dispatches.add(request.uri().getPath());
                started.complete(null);
                try {
                    String body = request.readBodyAsString();
                    completed.complete(body + "|" + request.trailers().get("x-sentinel"));
                    response.status(204);
                } catch (Throwable error) {
                    completed.completeExceptionally(error);
                }
                return true;
            }).start();
        var stopper = Executors.newSingleThreadExecutor();
        try (var client = new H2Client(); var con = client.connect(server)) {
            con.handshake();
            con.socket().setSoTimeout(3000);
            sendBlock(con, 1, false, request("/live", "POST"), false);
            started.get(5, TimeUnit.SECONDS);
            con.writeFrame(utf8DataFrame(1, false, "live-body")).flush();
            var stopping = stopper.submit(() -> server.stop());
            assertEquals(goAway(Integer.MAX_VALUE, Http2ErrorCode.NO_ERROR), con.readLogicalFrame());
            assertEquals(goAway(1, Http2ErrorCode.NO_ERROR), con.readLogicalFrame());
            byte[] discarded = concat(request("/discarded", "GET"), literal("x-sentinel", "From-Discard", true, false));
            if (corrupt) discarded = concat(discarded, new byte[]{(byte) 0x80});
            sendBlock(con, 3, true, discarded, fragmented);
            if (corrupt) {
                // A PING is an ordering barrier: ACK proves the corrupt block was silently discarded.
                con.writeFrame(new Http2Ping(false, new byte[8])).flush();
                var frames = new ArrayList<LogicalHttp2Frame>();
                for (int i = 0; i < 16; i++) {
                    var frame = con.readLogicalFrame();
                    frames.add(frame);
                    if (frame instanceof Http2GoAway || frame instanceof Http2Ping) break;
                }
                compressionError(frames);
                assertConnectionClosed(con);
            } else {
                var refused = readIgnoringWindowUpdates(con, Http2ResetStreamFrame.class);
                assertEquals(3, refused.streamId());
                assertEquals(Http2ErrorCode.REFUSED_STREAM, refused.errorCodeEnum());
                byte[] discardedData = new byte[16_384];
                con.writeFrame(new Http2DataFrame(3, false, discardedData, 0, discardedData.length))
                    .writeFrame(new Http2DataFrame(3, true, discardedData, 0, discardedData.length)).flush();
                var credit = con.readLogicalFrame();
                assertInstanceOf(Http2WindowUpdate.class, credit);
                assertEquals(0, credit.streamId(), "Discarded DATA returns only connection credit");
                assertTrue(((Http2WindowUpdate) credit).windowSizeIncrement() >= 32_768);
                sendBlock(con, 1, true, indexed(62), fragmented);
                var live = terminal(con, 1);
                assertAll(() -> successful(live, 1, "204"),
                    () -> assertEquals("live-body|From-Discard", completed.get(5, TimeUnit.SECONDS)),
                    () -> assertEquals(List.of("/live"), dispatches));
            }
            stopping.get(5, TimeUnit.SECONDS);
        } finally {
            stopper.shutdownNow();
        }
    }

    private static byte[] request(String path, String method) throws IOException {
        return concat(literal(":method", method, false, false), indexed(7),
            literal(":authority", "localhost", false, false), literal(":path", path, false, false));
    }

    private static List<LogicalHttp2Frame> terminal(H2ClientConnection con, int stream) throws Exception {
        var frames = new ArrayList<LogicalHttp2Frame>();
        for (int i = 0; i < 64; i++) {
            var frame = con.readLogicalFrame();
            frames.add(frame);
            if (frame instanceof Http2GoAway
                || frame instanceof Http2ResetStreamFrame && ((Http2ResetStreamFrame) frame).streamId() == stream
                || frame instanceof Http2HeadersFrame && ((Http2HeadersFrame) frame).streamId() == stream
                && ((Http2HeadersFrame) frame).endStream()
                || frame instanceof Http2DataFrame && ((Http2DataFrame) frame).streamId() == stream
                && ((Http2DataFrame) frame).endStream()) return frames;
        }
        fail("No terminal outcome in 64 frames for stream " + stream);
        return frames;
    }

    private static void successful(List<LogicalHttp2Frame> frames, int stream, String status) {
        assertFalse(frames.stream().anyMatch(f -> f instanceof Http2GoAway
            || f instanceof Http2ResetStreamFrame && ((Http2ResetStreamFrame) f).streamId() == stream), frames.toString());
        assertTrue(frames.stream().anyMatch(f -> f instanceof Http2HeadersFrame
            && ((Http2HeadersFrame) f).streamId() == stream
            && status.equals(((Http2HeadersFrame) f).headers().get(":status"))), frames.toString());
    }

    private static void compressionError(List<LogicalHttp2Frame> frames) {
        assertTrue(frames.stream().anyMatch(f -> f instanceof Http2GoAway
            && ((Http2GoAway) f).errorCodeEnum() == Http2ErrorCode.COMPRESSION_ERROR), frames.toString());
        assertFalse(frames.stream().anyMatch(f -> f instanceof Http2HeadersFrame || f instanceof Http2ResetStreamFrame
            && ((Http2ResetStreamFrame) f).errorCodeEnum() != Http2ErrorCode.REFUSED_STREAM),
            "Compression failure must not be reported as a recoverable HTTP error: " + frames);
    }

    private static void assertConnectionClosed(H2ClientConnection con) {
        IOException failure = assertThrows(IOException.class, con::readFrameHeader);
        assertFalse(failure instanceof SocketTimeoutException, "A silent open connection is not closure");
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }
}
