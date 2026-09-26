package io.muserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.muserver.FieldConformanceFixtures.*;
import static io.muserver.RFCTestUtils.*;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.veryTrustingTrustManager;
import static scaffolding.ServerUtils.httpsServerForTest;

/** Actual server emission only: there is no public HTTP/2 client validator or response-trailer writer. */
class ResponseFieldConformanceTest {
    private MuServer server;

    static Stream<Arguments> protocolsAndPaths() {
        return Stream.of("http", "https", "h2").flatMap(protocol ->
            Stream.of("scalar", "bulk", "copy", "request-copy").map(path -> Arguments.of(protocol, path)));
    }

    private static String opaque() {
        StringBuilder result = new StringBuilder();
        for (int c = 128; c <= 255; c++) result.append((char) c);
        return result.toString();
    }

    private static Map<String, List<String>> expected(String path) {
        var fields = new LinkedHashMap<String, List<String>>();
        fields.put("content-type", List.of("Text/Plain; Charset=UTF-8"));
        fields.put("x-custom", List.of("MiXeD  \t Case"));
        fields.put("x-empty", List.of(""));
        fields.put("x-ows-only", List.of(""));
        fields.put("x-opaque", List.of(opaque()));
        if (path.equals("bulk")) fields.put("x-bulk", List.of("", "First", "Last"));
        if (path.equals("request-copy")) fields.put("x-copy", List.of(opaque(), ""));
        return fields;
    }

    private static void populate(Headers headers, String path, MuRequest request) {
        Headers target = path.equals("copy") ? Headers.create() : headers;
        target.set(new StringBuilder("Content-Type"), "Text/Plain; Charset=UTF-8");
        target.add(new StringBuilder("X-Custom"), " \tMiXeD  \t Case\t ");
        target.set("X-Empty", "");
        target.set("X-Ows-Only", " \t ");
        target.add("X-Opaque", opaque());
        if (path.equals("bulk")) target.add("X-Bulk", List.of("", "First", "Last"));
        if (path.equals("request-copy")) {
            for (var entry : request.headers()) {
                if (entry.getKey().equalsIgnoreCase("x-copy")) {
                    target.add(entry.getKey(), entry.getValue());
                }
            }
        }
        if (path.equals("copy")) headers.setAll(Headers.create().add(target));
    }

    @ParameterizedTest
    @MethodSource("protocolsAndPaths")
    void finalAndInformationalSectionsPreservePublicHeaderContract(String protocol, String path) throws Exception {
        var handler = new CompletableFuture<Void>();
        server = httpsServerForTest(protocol.equals("h2") ? "https" : protocol)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                try {
                    Headers informational = Headers.create();
                    populate(informational, path, request);
                    response.sendInformationalResponse(HttpStatus.EARLY_HINTS_103, informational);
                    populate(response.headers(), path, request);
                    response.status(204);
                    handler.complete(null);
                } catch (Throwable failure) {
                    handler.completeExceptionally(failure);
                }
                return true;
            }).start();
        Map<String, List<String>> expected = expected(path);
        if (protocol.equals("h2")) {
            try (var client = new H2Client(); var con = client.connect(server)) {
                con.handshake();
                sendBlock(con, 1, true, concat(indexed(2), indexed(7), named(4, "/", false, false),
                    named(1, "localhost:" + server.uri().getPort(), false, false),
                    literal("x-copy", opaque(), false, false), literal("x-copy", "", false, false)), false);
                byte[] informational = readHeaderBlock(con, 1, false);
                byte[] finalResponse = readHeaderBlock(con, 1, true);
                handler.get(5, TimeUnit.SECONDS);
                var decoder = new FieldBlockDecoder(new HpackTable(4096), 32768, 32768);
                FieldBlock info = decoder.decodeFrom(ByteBuffer.wrap(informational));
                FieldBlock fin = decoder.decodeFrom(ByteBuffer.wrap(finalResponse));
                assertAll(
                    () -> assertHttp2Section(informational, info, "103", expected),
                    () -> assertHttp2Section(finalResponse, fin, "204", expected));
            }
        } else {
            try (Socket socket = connect(protocol)) {
                socket.getOutputStream().write(octets("GET / HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close\r\n" + (path.equals("request-copy")
                    ? "X-Copy: " + opaque() + "\r\nX-Copy:\r\n" : "") + "\r\n"));
                socket.getOutputStream().flush();
                String informational = readHttp1Section(socket.getInputStream());
                String finalResponse = readHttp1Section(socket.getInputStream());
                handler.get(5, TimeUnit.SECONDS);
                assertAll(
                    () -> assertHttp1Section(informational, 103, expected),
                    () -> assertHttp1Section(finalResponse, 204, expected),
                    () -> assertEquals(-1, socket.getInputStream().read(), "No body or extra response after 204"));
            }
        }
    }

    static Stream<Arguments> malformedApiFields() {
        return Stream.of("http", "https", "h2").flatMap(protocol -> Stream.of(
            Arguments.of(protocol, "Bad Name", "value", "scalar"),
            Arguments.of(protocol, "X:Injected", "value", "bulk"),
            Arguments.of(protocol, "\u212a-name", "value", "copy"),
            Arguments.of(protocol, "x-injected", "safe\r\nInjected: yes", "scalar"),
            Arguments.of(protocol, "x-injected", "\u0000value", "bulk"),
            Arguments.of(protocol, "x-injected", " \r ", "copy"),
            Arguments.of(protocol, "x-injected", "\u0100", "scalar"),
            Arguments.of(protocol, "x-injected", "\ud800", "bulk")));
    }

    @ParameterizedTest
    @MethodSource("malformedApiFields")
    void invalidApplicationFieldsFailBeforeMalformedOutput(String protocol, String name, String value,
                                                           String path) throws Exception {
        exerciseInvalidOutput(protocol, name, value, path, false);
    }

    @ParameterizedTest
    @MethodSource("malformedApiFields")
    void invalidInformationalFieldsFailBeforeMalformedOutput(String protocol, String name, String value,
                                                             String path) throws Exception {
        exerciseInvalidOutput(protocol, name, value, path, true);
    }

    private void exerciseInvalidOutput(String protocol, String name, String value, String path,
                                       boolean informational) throws Exception {
        var rejected = new CompletableFuture<Boolean>();
        server = httpsServerForTest(protocol.equals("h2") ? "https" : protocol)
            .withHttp2Config(Http2ConfigBuilder.http2Enabled())
            .addHandler((request, response) -> {
                boolean failed = false;
                Headers target = informational ? Headers.create() : response.headers();
                try {
                    if (path.equals("bulk")) target.add(name, List.of(value));
                    else if (path.equals("copy")) target.add(Headers.create().set(name, value));
                    else target.set(name, value);
                } catch (IllegalArgumentException expected) {
                    failed = true;
                }
                if (informational) response.sendInformationalResponse(HttpStatus.EARLY_HINTS_103, target);
                response.status(204);
                rejected.complete(failed);
                return true;
            }).start();
        if (protocol.equals("h2")) {
            try (var client = new H2Client(); var con = client.connect(server)) {
                con.handshake().writeFrame(new Http2HeadersFrame(1, true, getHelloHeaders(server.uri().getPort())))
                    .flush();
                byte[] block = readHeaderBlock(con, 1, !informational);
                var decoder = new FieldBlockDecoder(new HpackTable(4096), 32768, 32768);
                FieldBlock fields = decoder.decodeFrom(ByteBuffer.wrap(block));
                if (informational) {
                    assertEquals("204", decoder.decodeFrom(ByteBuffer.wrap(readHeaderBlock(con, 1, true))).get(":status"));
                }
                assertAll(
                    () -> assertTrue(rejected.get(5, TimeUnit.SECONDS), "Public mutation must reject invalid input"),
                    () -> assertEquals(informational ? "103" : "204", fields.get(":status")),
                    () -> assertFalse(new String(block, StandardCharsets.ISO_8859_1).contains("Injected")),
                    () -> assertNull(fields.get("x-injected")),
                    () -> assertFalse(fields.entries().stream().anyMatch(e -> e.getKey().equalsIgnoreCase("bad name"))),
                    () -> assertNull(fields.get("k-name")));
            }
        } else {
            try (Socket socket = connect(protocol)) {
                socket.getOutputStream().write(octets("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"));
                socket.getOutputStream().flush();
                String section = readHttp1Section(socket.getInputStream());
                if (informational) {
                    assertTrue(readHttp1Section(socket.getInputStream()).startsWith("HTTP/1.1 204 "));
                }
                assertAll(
                    () -> assertTrue(rejected.get(5, TimeUnit.SECONDS), "Public mutation must reject invalid input"),
                    () -> assertTrue(section.startsWith("HTTP/1.1 " + (informational ? "103" : "204") + " ")),
                    () -> assertFalse(section.contains("Injected")),
                    () -> assertFalse(section.toLowerCase(Locale.ROOT).contains("x-injected")),
                    () -> assertFalse(section.toLowerCase(Locale.ROOT).contains("bad name")),
                    () -> assertFalse(section.toLowerCase(Locale.ROOT).contains("k-name")),
                    () -> assertEquals(-1, socket.getInputStream().read()));
            }
        }
    }

    private static void assertHttp1Section(String section, int status, Map<String, List<String>> expected) {
        String[] lines = section.split("\r\n", -1);
        assertTrue(lines[0].startsWith("HTTP/1.1 " + status + " "), section);
        var actual = new LinkedHashMap<String, List<String>>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            int colon = lines[i].indexOf(':');
            assertTrue(colon > 0, "No pseudo header, folding, or malformed line: " + lines[i]);
            String name = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
            assertTrue(name.chars().allMatch(c -> TOKEN.indexOf(c) >= 0), name);
            String value = lines[i].substring(colon + 1);
            // Remove the writer's single formatting SP, not arbitrary input whitespace.
            if (value.startsWith(" ")) value = value.substring(1);
            actual.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        assertAll(expected.entrySet().stream().map(entry ->
            (org.junit.jupiter.api.function.Executable) () ->
                assertEquals(entry.getValue(), actual.get(entry.getKey()), entry.getKey())));
    }

    private static void assertHttp2Section(byte[] raw, FieldBlock fields, String status,
                                           Map<String, List<String>> expected) {
        var entries = new ArrayList<Map.Entry<String, String>>();
        for (var entry : fields) entries.add(entry);
        var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
        checks.add(() -> assertFalse(entries.isEmpty()));
        checks.add(() -> assertEquals(":status", entries.get(0).getKey()));
        checks.add(() -> assertEquals(status, entries.get(0).getValue()));
        checks.add(() -> assertEquals(1, entries.stream().filter(e -> e.getKey().startsWith(":")).count()));
        for (var entry : entries) {
            if (!entry.getKey().startsWith(":")) {
                checks.add(() -> assertEquals(entry.getKey().toLowerCase(Locale.ROOT), entry.getKey()));
                checks.add(() -> assertTrue(entry.getKey().chars().allMatch(c -> TOKEN.indexOf(c) >= 0)));
            }
        }
        for (var entry : expected.entrySet()) {
            checks.add(() -> assertEquals(entry.getValue(), fields.getAll(entry.getKey()), entry.getKey()));
            for (String value : entry.getValue()) {
                checks.add(() -> {
                    // Independent of shared decoding, which can conceal uppercase built-in names.
                    // This writer currently emits non-Huffman literals.
                    byte[] literal = entry.getKey().equals("content-type")
                        ? named(31, value, false, false) : literal(entry.getKey(), value, false, false);
                    assertTrue(contains(raw, literal), "Missing exact lowercase/opaque wire field " + entry.getKey());
                });
            }
        }
        assertAll(checks);
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private static byte[] readHeaderBlock(H2ClientConnection con, int stream, boolean finalSection) throws Exception {
        var out = new ByteArrayOutputStream();
        boolean started = false;
        for (int count = 0; count < 32; count++) {
            Http2FrameHeader frame = con.readFrameHeader();
            if (!started && frame.frameType() == Http2FrameType.WINDOW_UPDATE) {
                con.readRawPayload(frame);
                continue;
            }
            assertEquals(started ? Http2FrameType.CONTINUATION : Http2FrameType.HEADERS, frame.frameType());
            assertEquals(stream, frame.streamId());
            if (!started) assertEquals(finalSection, (frame.flags() & 1) != 0);
            out.writeBytes(con.readRawPayload(frame));
            assertTrue(out.size() <= 32768, "Bound response field bytes");
            started = true;
            if ((frame.flags() & 4) != 0) return out.toByteArray();
        }
        throw new AssertionError("No complete response field section in 32 frames");
    }

    private static String readHttp1Section(InputStream input) throws Exception {
        var out = new ByteArrayOutputStream();
        int suffix = 0;
        while (out.size() < 32768) {
            int b = input.read();
            assertNotEquals(-1, b, "Truncated response field section");
            out.write(b);
            suffix = (suffix << 8) | b;
            if (suffix == 0x0d0a0d0a) return out.toString(StandardCharsets.ISO_8859_1);
        }
        throw new AssertionError("Response fields exceeded 32768 bytes");
    }

    private Socket connect(String protocol) throws Exception {
        Socket socket;
        if (protocol.equals("https")) {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{veryTrustingTrustManager()}, null);
            socket = context.getSocketFactory().createSocket("localhost", server.uri().getPort());
        } else {
            socket = new Socket("localhost", server.uri().getPort());
        }
        socket.setSoTimeout(3000);
        return socket;
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop();
    }
}
