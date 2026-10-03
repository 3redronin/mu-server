package io.muserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import scaffolding.Http1Client;
import scaffolding.SingleCarrier;

import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.Socket;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ServerUtils.httpsServerForTest;

/** Valid traffic and backpressure checks; deadlines are hang guards, not latency targets. */
public class ApplicationCarrierProgressTest {
    @TempDir Path temporary;

    static Stream<Arguments> cases() {
        var cases = new ArrayList<Arguments>();
        for (int carriers : new int[]{1, 2}) {
            for (boolean h2 : new boolean[]{false, true}) {
                for (String endpoint : List.of("text", "json", "stream", "input", "reader", "sse", "upload", "reader-upload")) {
                    for (boolean gzip : new boolean[]{false, true}) {
                        if (gzip && (endpoint.equals("sse") || endpoint.endsWith("upload"))) continue;
                        for (String ending : List.of("resume", "cancel", "shutdown")) {
                            cases.add(Arguments.of(carriers, h2, endpoint, gzip, ending));
                        }
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "carriers={0}, h2={1}, {2}, gzip={3}, {4}")
    @MethodSource("cases")
    void blockedApplicationLeavesCarriersAvailable(int carriers, boolean h2, String endpoint,
                                                   boolean gzip, String ending) throws Exception {
        SingleCarrier.run(ApplicationCarrierProgressTest.class, temporary.resolve("child.log"), carriers, 90,
            Boolean.toString(h2), endpoint, Boolean.toString(gzip), ending);
    }

    @jakarta.ws.rs.Path("/")
    public static class Resource {
        final String text;
        final byte[] bytes;
        final AtomicReference<Thread> handler = new AtomicReference<>();

        Resource(int size) {
            byte[] random = new byte[size];
            var rng = new Random(946);
            for (int i = 0; i < random.length; i++) random[i] = (byte) ('a' + rng.nextInt(26));
            bytes = random;
            text = new String(random, UTF_8);
        }

        void entered() {
            try {
                assertEquals(true, Thread.class.getMethod("isVirtual").invoke(Thread.currentThread()),
                    "The workload must actually use a virtual handler");
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            handler.set(Thread.currentThread());
        }

        @GET @jakarta.ws.rs.Path("text") @Produces("text/plain")
        public String text() { entered(); return text; }

        @GET @jakarta.ws.rs.Path("json") @Produces("application/json")
        public Map<String, String> json() { entered(); return Map.of("message", text); }

        @GET @jakarta.ws.rs.Path("stream") @Produces("text/plain")
        public StreamingOutput stream() {
            entered();
            return out -> {
                for (int offset = 0; offset < bytes.length; offset += 8192) {
                    out.write(bytes, offset, Math.min(8192, bytes.length - offset));
                }
            };
        }

        @GET @jakarta.ws.rs.Path("input") @Produces("text/plain")
        public InputStream input() { entered(); return new ByteArrayInputStream(bytes); }

        @GET @jakarta.ws.rs.Path("reader") @Produces("text/plain")
        public Reader reader() { entered(); return new StringReader(text); }

        @POST @jakarta.ws.rs.Path("upload") @Consumes("application/octet-stream") @Produces("text/plain")
        public String upload(InputStream input) throws IOException {
            entered();
            return new String(input.readAllBytes(), UTF_8);
        }

        @POST @jakarta.ws.rs.Path("reader-upload") @Consumes("application/octet-stream") @Produces("text/plain")
        public String readerUpload(Reader input) throws IOException {
            entered();
            var result = new StringWriter();
            input.transferTo(result);
            return result.toString();
        }

        @GET @jakarta.ws.rs.Path("sse") @Produces(MediaType.SERVER_SENT_EVENTS)
        public void sse(@Context SseEventSink sink, @Context Sse sse) throws Exception {
            entered();
            try {
                // Waiting for each send applies backpressure instead of building an unbounded event queue.
                for (int offset = 0; offset < text.length(); offset += 8192) {
                    sink.send(sse.newEvent(text.substring(offset, Math.min(offset + 8192, text.length()))))
                        .toCompletableFuture().get(30, TimeUnit.SECONDS);
                }
            } finally { sink.close(); }
        }
    }

    /** A normal streaming Jackson integration, with no pre-serialized response body. */
    @Produces("application/json")
    public static class JsonWriter implements MessageBodyWriter<Map<String, String>> {
        private final ObjectMapper mapper = new ObjectMapper();
        @Override public boolean isWriteable(Class<?> type, Type generic, Annotation[] annotations, MediaType media) {
            return Map.class.isAssignableFrom(type);
        }
        @Override public void writeTo(Map<String, String> value, Class<?> type, Type generic, Annotation[] annotations,
                                      MediaType media, MultivaluedMap<String, Object> headers, OutputStream out) throws IOException {
            mapper.writeValue(out, value);
        }
    }

    public static void main(String[] args) throws Exception {
        boolean h2 = Boolean.parseBoolean(args[0]);
        String endpoint = args[1];
        boolean gzip = Boolean.parseBoolean(args[2]);
        String ending = args[3];
        // H2 has a deterministic zero stream window. HTTP/1 needs to fill the real socket buffers.
        var resource = new Resource(h2 ? 256 * 1024 : 8 * 1024 * 1024);
        var server = httpsServerForTest(h2 ? "h2" : "http")
            .withThreadingMode(ThreadingMode.AUTO)
            .withIdleTimeout(60, TimeUnit.SECONDS).withRequestTimeout(60, TimeUnit.SECONDS)
            .addHandler((req, resp) -> {
                if (!req.uri().getPath().equals("/probe")) return false;
                resp.write("ok");
                return true;
            })
            .addHandler(restHandler(resource).addCustomWriter(new JsonWriter())).start();
        try (Peer blocked = new Peer(server, h2, true)) {
            blocked.send(1, endpoint, gzip);
            awaitBlocked(resource, endpoint);
            // A fresh connection exercises accepting, TLS, dispatch and writing as well as the handler.
            try (Peer probe = new Peer(server, h2, false)) {
                probe.send(1, "probe", false);
                assertEquals("ok", new String(probe.read(1, false), UTF_8));
            }
            // H2 must also progress on another stream of the blocked connection.
            if (h2) {
                blocked.send(3, "probe", false);
                blocked.h2.writeFrame(new Http2WindowUpdate(3, 65535)).flush();
                assertEquals("ok", new String(blocked.read(3, false), UTF_8));
            }
            awaitBlocked(resource, endpoint);
            if (ending.equals("resume")) {
                if (endpoint.endsWith("upload")) blocked.finishUpload();
                if (h2) blocked.h2.writeFrame(new Http2WindowUpdate(1, 65535)).flush();
                byte[] actual = blocked.read(1, gzip);
                if (endpoint.equals("json")) {
                    assertEquals(resource.text, new ObjectMapper().readTree(actual).get("message").asText());
                } else if (endpoint.equals("sse")) {
                    StringBuilder expected = new StringBuilder();
                    for (int offset = 0; offset < resource.text.length(); offset += 8192) {
                        expected.append("data: ").append(resource.text, offset, Math.min(offset + 8192, resource.text.length())).append("\n\n");
                    }
                    assertArrayEquals(expected.toString().getBytes(UTF_8), actual);
                } else {
                    assertArrayEquals(endpoint.endsWith("upload") ? "first-last".getBytes(UTF_8) : resource.bytes, actual);
                }
            } else if (ending.equals("cancel")) {
                blocked.cancel();
            } else {
                assertFalse(server.stop(0, TimeUnit.SECONDS), "Shutdown should have to close the blocked exchange");
            }
            await(() -> !resource.handler.get().isAlive(), "Application did not exit after " + ending);
            await(() -> server.stats().activeRequests().isEmpty(), "Request tracking did not drain after " + ending);
            if (h2 && ending.equals("cancel")) {
                blocked.send(5, "probe", false);
                blocked.h2.writeFrame(new Http2WindowUpdate(5, 65535)).flush();
                assertEquals("ok", new String(blocked.read(5, false), UTF_8));
            }
            if (!ending.equals("shutdown")) {
                try (Peer probe = new Peer(server, h2, false)) {
                    probe.send(1, "probe", false);
                    assertEquals("ok", new String(probe.read(1, false), UTF_8));
                }
            }
        } finally { server.stop(0, TimeUnit.SECONDS); }
        System.out.println("PASS " + String.join(" ", args));
    }

    private static void awaitBlocked(Resource resource, String endpoint) throws Exception {
        await(() -> {
            Thread t = resource.handler.get();
            if (t == null || (t.getState() != Thread.State.WAITING && t.getState() != Thread.State.TIMED_WAITING)) return false;
            // These stack frames demonstrate the application reached real body/socket/future I/O.
            return Arrays.stream(t.getStackTrace()).anyMatch(frame ->
                frame.getClassName().startsWith("io.muserver.") &&
                    (frame.getClassName().contains("Stream") || frame.getClassName().contains("Write") ||
                     (endpoint.equals("sse") && frame.getMethodName().equals("sse"))));
        }, "Application never blocked in " + endpoint);
        System.out.println("Blocked application: " + Arrays.toString(resource.handler.get().getStackTrace()));
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail(message);
    }

    /** Both clients run on the child's platform main thread, independent of Mu's carrier scheduler. */
    private static class Peer implements AutoCloseable {
        final MuServer server;
        final H2ClientConnection h2;
        final Http1Client h1;
        final Socket socket;
        FieldBlock responseHeaders;

        Peer(MuServer server, boolean http2, boolean blocked) throws Exception {
            this.server = server;
            if (http2) {
                h2 = new H2Client().connect(server);
                socket = h2.socket();
                socket.setSoTimeout(20000);
                h2.handshake(new Http2Settings(false, 4096, 100, blocked ? 0 : 65535, 16384, 32768));
                h1 = null;
            } else {
                h2 = null;
                socket = new Socket(server.uri().getHost(), server.uri().getPort());
                socket.setSoTimeout(20000);
                h1 = new Http1Client(socket, new BufferedInputStream(socket.getInputStream()), socket.getOutputStream(), server.uri());
            }
        }

        void send(int stream, String endpoint, boolean gzip) throws Exception {
            boolean upload = endpoint.endsWith("upload");
            if (h2 != null) {
                var headers = RFCTestUtils.getHelloHeaders("https", server.uri().getPort());
                headers.set(":path", "/" + endpoint);
                headers.set(":method", upload ? "POST" : "GET");
                headers.set("accept-encoding", gzip ? "gzip" : "identity");
                if (upload) {
                    headers.set("content-type", "application/octet-stream");
                    headers.set("content-length", "10");
                }
                h2.writeFrame(new Http2HeadersFrame(stream, !upload, headers));
                if (upload) h2.writeFrame(RFCTestUtils.utf8DataFrame(stream, false, "first-"));
                h2.flush();
            } else {
                h1.writeRequestLine(upload ? Method.POST : Method.GET, "/" + endpoint)
                    .writeHeader("accept-encoding", gzip ? "gzip" : "identity");
                if (upload) h1.contentHeader("application/octet-stream", 10);
                h1.endHeaders();
                if (upload) h1.writeAscii("first-");
                h1.flush();
            }
        }

        void finishUpload() throws Exception {
            if (h2 != null) h2.writeFrame(RFCTestUtils.utf8DataFrame(1, true, "last")).flush();
            else h1.writeAscii("last").flush();
        }

        byte[] read(int stream, boolean gzip) throws Exception {
            var bytes = new ByteArrayOutputStream();
            String encoding = null;
            if (h2 != null) {
                boolean gotHeaders = false;
                while (true) {
                    var frame = h2.readLogicalFrame();
                    if (frame instanceof Http2WindowUpdate) continue;
                    if (frame instanceof Http2HeadersFrame) {
                        var headers = (Http2HeadersFrame) frame;
                        assertEquals("200", headers.headers().get(":status"));
                        if (frame.streamId() != stream) {
                            assertEquals(1, frame.streamId());
                            responseHeaders = headers.headers();
                            continue;
                        }
                        gotHeaders = true;
                        encoding = headers.headers().get("content-encoding");
                        if (headers.endStream()) break;
                    } else {
                        assertInstanceOf(Http2DataFrame.class, frame);
                        assertEquals(stream, frame.streamId());
                        var data = (Http2DataFrame) frame;
                        bytes.write(data.payload());
                        if (data.payload().length > 0) {
                            h2.writeFrame(new Http2WindowUpdate(0, data.payload().length));
                            if (!data.endStream()) h2.writeFrame(new Http2WindowUpdate(stream, data.payload().length));
                            h2.flush();
                        }
                        if (data.endStream()) break;
                    }
                }
                if (!gotHeaders) {
                    assertNotNull(responseHeaders, "Missing response headers");
                    encoding = responseHeaders.get("content-encoding");
                }
            } else {
                assertEquals("HTTP/1.1 200 OK", h1.readLine());
                Headers headers = h1.readHeaders();
                encoding = headers.get(HeaderNames.CONTENT_ENCODING);
                if (headers.contains(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED, true)) {
                    while (true) {
                        int length = Integer.parseInt(h1.readLine().split(";", 2)[0], 16);
                        if (length == 0) { assertEquals("", h1.readLine()); break; }
                        byte[] chunk = h1.in().readNBytes(length);
                        assertEquals(length, chunk.length);
                        bytes.write(chunk);
                        assertEquals("", h1.readLine());
                    }
                } else {
                    int length = headers.getInt(HeaderNames.CONTENT_LENGTH, 0);
                    bytes.write(h1.in().readNBytes(length));
                    assertEquals(length, bytes.size());
                }
            }
            assertEquals(gzip ? "gzip" : null, encoding);
            if (!gzip) return bytes.toByteArray();
            try (var decoded = new GZIPInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return decoded.readAllBytes();
            }
        }

        void cancel() throws Exception {
            if (h2 != null) h2.writeFrame(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code())).flush();
            else { socket.setSoLinger(true, 0); socket.close(); }
        }

        @Override public void close() throws Exception { if (h2 != null) h2.close(); else h1.close(); }
    }
}
