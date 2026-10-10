package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static io.muserver.RFCTestUtils.*;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

@Timeout(30)
class Http2ReadDriverTest {
    private static byte[] preface() throws Exception {
        var out = new ByteArrayOutputStream();
        out.write("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(US_ASCII));
        Http2Settings.DEFAULT_CLIENT_SETTINGS.writeTo(null, out);
        Http2Settings.ACK.writeTo(null, out);
        return out.toByteArray();
    }

    private static byte[] requestBytes(int id) throws Exception {
        return headersFrame(id, true, true, encodeFieldBlock(getHelloHeaders("http", 80)));
    }

    @Test
    void everyTransportSplitAcrossPrefaceFramesAndContinuationCanResume() throws Exception {
        var handled = new AtomicInteger();
        byte[] block = encodeFieldBlock(getHelloHeaders("http", 80));
        var bytes = new ByteArrayOutputStream();
        bytes.write(preface());
        bytes.write(headersFrame(1, true, false, Arrays.copyOfRange(block, 0, 3)));
        bytes.write(continuationFrame(1, true, Arrays.copyOfRange(block, 3, block.length)));
        bytes.write(requestBytes(3));
        byte[] wire = bytes.toByteArray();
        try (var fixture = new Fixture((req, res) -> { handled.incrementAndGet(); return true; })) {
            for (int split = 0; split <= wire.length; split++) {
                Session session = fixture.add(wire.length);
                session.offer(Arrays.copyOfRange(wire, 0, split));
                fixture.barrier(); // The partial read returns its only worker.
                session.offer(Arrays.copyOfRange(wire, split, wire.length));
                fixture.until(() -> session.connection.completedRequests() == 2);
                session.end();
                session.driver.completion().get(3, TimeUnit.SECONDS);
                assertTrue(session.connection.testProbe().streams().isEmpty());
                assertTrue(session.connection.testProbe().pendingSettingsAcks().isEmpty());
            }
            assertEquals((wire.length + 1) * 2, handled.get());
        }
    }

    @Test
    void eofAtEveryIncompleteTransportPositionRetiresTheReader() throws Exception {
        var bytes = new ByteArrayOutputStream();
        bytes.write(preface());
        byte[] block = encodeFieldBlock(getHelloHeaders("http", 80));
        bytes.write(headersFrame(1, true, false, Arrays.copyOfRange(block, 0, 3)));
        bytes.write(continuationFrame(1, true, Arrays.copyOfRange(block, 3, block.length)));
        byte[] wire = bytes.toByteArray();
        try (var fixture = new Fixture((req, res) -> { fail("Incomplete headers dispatched a request"); return true; })) {
            for (int cut = 0; cut < wire.length; cut++) {
                Session session = fixture.add(wire.length);
                session.offer(Arrays.copyOf(wire, cut));
                session.end();
                session.driver.completion().get(3, TimeUnit.SECONDS);
                assertTrue(session.closed);
                assertTrue(session.connection.activeRequests().isEmpty());
            }
        }
    }

    @Test
    void idlePartialAndSuspendedConnectionsShareOneInternalWorker() throws Exception {
        Queue<AsyncHandle> handles = new ConcurrentLinkedQueue<>();
        try (var fixture = new Fixture((req, res) -> { handles.add(req.handleAsync()); return true; })) {
            for (int i = 0; i < 128; i++) {
                Session session = fixture.add(1024);
                session.offer(preface());
            }
            fixture.barrier();
            byte[] first = requestBytes(1);
            for (Session session : fixture.sessions) session.offer(Arrays.copyOf(first, first.length - 1));
            fixture.barrier();
            assertTrue(handles.isEmpty());
            for (Session session : fixture.sessions) session.offer(new byte[]{first[first.length - 1]});
            fixture.until(() -> handles.size() == 128);
            fixture.barrier();
            for (Session session : fixture.sessions) assertFalse(session.driver.completion().isDone());
            AsyncHandle handle;
            while ((handle = handles.poll()) != null) handle.complete();
            fixture.until(() -> fixture.sessions.stream().allMatch(s -> s.connection.completedRequests() == 1));
            fixture.barrier();
            assertEquals(1, fixture.internalThreads.get());
            assertEquals(1, fixture.applicationThreads.get());
            for (Session session : fixture.sessions) session.end();
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 7, 17, 8192})
    void boundedInputCompactsLargePhysicalPayloadsAndPreservesFollowingFrames(int capacity) throws Exception {
        CompletableFuture<String> body = new CompletableFuture<>();
        try (var fixture = new Fixture((req, res) -> { body.complete(req.readBodyAsString()); return true; })) {
            Session session = fixture.add(capacity);
            var wire = new ByteArrayOutputStream();
            wire.write(preface());
            FieldBlock headers = getHelloHeaders("http", 80);
            headers.set(":method", "POST");
            wire.write(headersFrame(1, false, true, encodeFieldBlock(headers)));
            // Exercise a full-size payload, then an unrelated PING in the same feed.
            String text = "abcde".repeat(3276) + "tail";
            byte[] data = text.getBytes(US_ASCII);
            wire.write(rawFrame(Http2FrameType.DATA, 1, 1, data));
            wire.write(rawFrame(Http2FrameType.PING, 0, 0, new byte[]{1,2,3,4,5,6,7,8}));
            session.feed(wire.toByteArray(), fixture);
            assertEquals(text, body.get(3, TimeUnit.SECONDS));
            fixture.until(() -> session.connection.completedRequests() == 1);
            fixture.barrier();
            assertTrue(containsFrame(session.output.toByteArray(), Http2FrameType.PING, 1));
        }
    }

    @Test
    void rejectionOfAResumedReaderCancelsActiveWorkOffTheNotifyingThread() throws Exception {
        CompletableFuture<AsyncHandle> handle = new CompletableFuture<>();
        CompletableFuture<String> completionThread = new CompletableFuture<>();
        try (var fixture = new Fixture((req, res) -> {
            res.addCompletionListener(info -> completionThread.complete(Thread.currentThread().getName()));
            handle.complete(req.handleAsync()); return true;
        })) {
            Session session = fixture.add(1024);
            session.offer(preface());
            session.offer(requestBytes(1));
            handle.get(3, TimeUnit.SECONDS);
            fixture.barrier();
            fixture.internal.shutdown();
            assertTrue(fixture.internal.awaitTermination(3, TimeUnit.SECONDS));
            session.input.fail(new IOException("injected input failure"));
            session.driver.inputAvailable();
            session.driver.completion().get(3, TimeUnit.SECONDS);
            assertTrue(completionThread.get(3, TimeUnit.SECONDS).startsWith("h2-reader-application-"));
            assertTrue(session.connection.activeRequests().isEmpty());
        }
    }

    @Test
    void pendingHandshakeAndFinalResponseDoNotPublishCompletionBeforeDrain() throws Exception {
        CompletableFuture<Http2Connection> handled = new CompletableFuture<>();
        CompletableFuture<ResponseInfo> completed = new CompletableFuture<>();
        try (var fixture = new Fixture((req, res) -> {
            res.addCompletionListener(completed::complete);
            res.status(204);
            handled.complete((Http2Connection) req.connection());
            return true;
        })) {
            Session session = fixture.add(1024, true);
            long before = fixture.server.stats().bytesSent();
            session.offer(preface());
            session.offer(requestBytes(1));
            fixture.until(() -> !session.pendingOutput.isEmpty());
            fixture.barrier();
            for (int i = 0; i < 1000; i++) session.driver.inputAvailable();
            fixture.barrier();
            assertFalse(handled.isDone(), "Queued input must wait for the initial SETTINGS to drain");
            assertEquals(before, fixture.server.stats().bytesSent());
            assertTrue(session.connection.testProbe().pendingSettingsAcks().isEmpty());
            session.completeOutput();
            handled.get(3, TimeUnit.SECONDS);
            fixture.until(() -> !session.pendingOutput.isEmpty());
            fixture.internal.submit(() -> {}).get(3, TimeUnit.SECONDS);
            assertFalse(completed.isDone());
            assertEquals(before + 48, fixture.server.stats().bytesSent());
            session.end(); // The in-flight final response retains its own output outcome.
            fixture.internal.submit(() -> {}).get(3, TimeUnit.SECONDS);
            assertFalse(session.driver.completion().isDone());
            session.completeOutput();
            assertTrue(completed.get(3, TimeUnit.SECONDS).completedSuccessfully());
            session.driver.completion().get(3, TimeUnit.SECONDS);
            assertEquals(before + session.output.size(), fixture.server.stats().bytesSent());
        }
    }

    @Test
    void shutdownReleasesAStalledInitialSettingsWriteWithoutDispatchingBufferedHeaders() throws Exception {
        try (var fixture = new Fixture((req, res) -> { fail("Shutdown must prevent dispatch"); return true; })) {
            Session session = fixture.add(1024, true);
            long before = fixture.server.stats().bytesSent();
            session.offer(preface());
            session.offer(requestBytes(1));
            fixture.until(() -> !session.pendingOutput.isEmpty());
            fixture.barrier();
            session.connection.forceShutdown();
            session.driver.completion().get(3, TimeUnit.SECONDS);
            assertTrue(session.closed);
            assertTrue(session.pendingOutput.isEmpty());
            assertTrue(session.connection.testProbe().pendingSettingsAcks().isEmpty());
            assertEquals(before, fixture.server.stats().bytesSent());
        }
    }

    @Test
    void malformedPrefaceDrainsItsGoAwayBeforeRetirement() throws Exception {
        try (var fixture = new Fixture((req, res) -> { fail("Malformed preface dispatched a request"); return true; })) {
            Session session = fixture.add(1024, true);
            session.offer(Arrays.copyOf(preface(), 24));
            session.offer(rawFrame(Http2FrameType.PING, 0, 0, new byte[8]));
            fixture.until(() -> !session.pendingOutput.isEmpty());
            fixture.barrier();
            assertFalse(session.closed);
            assertFalse(session.driver.completion().isDone());
            session.completeOutput();
            session.driver.completion().get(3, TimeUnit.SECONDS);
            ByteBuffer bytes = ByteBuffer.wrap(session.output.toByteArray());
            Http2FrameHeader header = Http2FrameHeader.readFrom(bytes);
            assertEquals(Http2FrameType.GOAWAY, header.frameType());
            assertEquals(Http2ErrorCode.PROTOCOL_ERROR.code(), Http2GoAway.readFrom(header, bytes).errorCode());
        }
    }

    private static byte[] rawFrame(Http2FrameType type, int flags, int id, byte[] payload) {
        return ByteBuffer.allocate(9 + payload.length).put((byte) (payload.length >> 16))
            .put((byte) (payload.length >> 8)).put((byte) payload.length).put(type.byteCode())
            .put((byte) flags).putInt(id).put(payload).array();
    }

    private static boolean containsFrame(byte[] bytes, Http2FrameType type, int flags) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.remaining() >= 9) {
            int length = ((buffer.get() & 255) << 16) | ((buffer.get() & 255) << 8) | (buffer.get() & 255);
            byte actualType = buffer.get(), actualFlags = buffer.get();
            buffer.getInt();
            if (actualType == type.byteCode() && actualFlags == flags) return true;
            buffer.position(buffer.position() + length);
        }
        return false;
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger internalThreads = new AtomicInteger(), applicationThreads = new AtomicInteger();
        final ExecutorService internal = Executors.newSingleThreadExecutor(r -> new Thread(r, "h2-reader-internal-" + internalThreads.incrementAndGet()));
        final ExecutorService application = Executors.newSingleThreadExecutor(r -> new Thread(r, "h2-reader-application-" + applicationThreads.incrementAndGet()));
        final List<Session> sessions = new ArrayList<>();
        final MuServer server;
        final BaseHttpConnection config;
        Fixture(MuHandler handler) throws Exception {
            var captured = new AtomicReference<BaseHttpConnection>();
            server = TestExecutionResources.configure(MuServerBuilder.httpServer(), null, null, internal, null)
                .withHandlerExecutor(application).addHandler((req, res) -> {
                    if (req.uri().getPath().equals("/fixture")) {
                        captured.set((BaseHttpConnection) req.connection()); res.write("ready"); return true;
                    }
                    return handler.handle(req, res);
                }).start();
            try (var response = call(request(server.uri().resolve("/fixture")).header("Connection", "close"))) {
                assertEquals("ready", response.body().string());
            }
            config = captured.get();
            barrier();
        }
        Session add(int capacity) { return add(capacity, false); }
        Session add(int capacity, boolean delayed) {
            Session result = new Session(this, capacity, delayed); sessions.add(result); return result;
        }
        void barrier() throws Exception {
            internal.submit(() -> {}).get(3, TimeUnit.SECONDS);
            application.submit(() -> {}).get(3, TimeUnit.SECONDS);
            internal.submit(() -> {}).get(3, TimeUnit.SECONDS);
        }
        void until(BooleanSupplier ready) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!ready.getAsBoolean()) {
                assertTrue(System.nanoTime() < deadline, "Protocol progression timed out");
                Thread.sleep(1);
            }
        }
        @Override public void close() throws Exception {
            try {
                for (Session session : sessions) { session.connection.forceShutdown(); session.driver.inputAvailable(); }
                for (Session session : sessions) session.driver.completion().get(3, TimeUnit.SECONDS);
            } finally {
                server.stop();
                internal.shutdownNow(); application.shutdownNow();
                assertTrue(internal.awaitTermination(3, TimeUnit.SECONDS));
                assertTrue(application.awaitTermination(3, TimeUnit.SECONDS));
            }
        }
    }

    private static final class Session implements ConnectionTransport {
        final Semaphore capacity = new Semaphore(0);
        final TransportInputBuffer input;
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final Queue<PendingOutput> pendingOutput = new ConcurrentLinkedQueue<>();
        final Http2Connection connection;
        final Http2Connection.ReadDriver driver;
        volatile boolean closed;
        Session(Fixture fixture, int capacity, boolean delayed) {
            input = new TransportInputBuffer(capacity, this.capacity::release);
            connection = new Http2Connection(fixture.config.server, fixture.config.creator, this,
                ConnectionAcceptedTime.now(), null, Http2Settings.DEFAULT_CLIENT_SETTINGS, 30000,
                fixture.application, fixture.internal);
            driver = connection.readDriver(input, source -> {
                if (delayed) {
                    var write = new PendingOutput(source);
                    pendingOutput.add(write);
                    return write.completion;
                }
                byte[] bytes = new byte[source.remaining()];
                source.duplicate().get(bytes);
                output.write(bytes);
                return CompletableFuture.completedFuture(null);
            });
        }
        void completeOutput() {
            PendingOutput write = java.util.Objects.requireNonNull(pendingOutput.poll());
            byte[] bytes = new byte[write.bytes.remaining()];
            write.bytes.get(bytes);
            output.writeBytes(bytes);
            write.completion.complete(null);
        }
        void offer(byte[] bytes) throws IOException {
            assertEquals(bytes.length, input.offer(ByteBuffer.wrap(bytes)));
            connection.onBytesRead(bytes.length);
            driver.inputAvailable();
        }
        void feed(byte[] bytes, Fixture fixture) throws Exception {
            ByteBuffer source = ByteBuffer.wrap(bytes);
            while (source.hasRemaining()) {
                int count = input.offer(source);
                connection.onBytesRead(count);
                driver.inputAvailable();
                if (count == 0) assertTrue(capacity.tryAcquire(3, TimeUnit.SECONDS), "Input reader did not return capacity");
            }
        }
        void end() { input.endOfInput(); driver.inputAvailable(); }
        @Override public InetSocketAddress remoteAddress() { return new InetSocketAddress("192.0.2.1", 1234); }
        @Override public InetSocketAddress localAddress() { return new InetSocketAddress("127.0.0.1", 80); }
        @Override public boolean isSecure() { return false; }
        @Override public String tlsProtocol() { return null; }
        @Override public String cipherSuite() { return null; }
        @Override public String sniHostName() { return null; }
        @Override public Certificate clientCertificate() { return null; }
        @Override public void readTimeoutMillis(int timeoutMillis) { input.readTimeoutMillis(timeoutMillis); }
        @Override public void shutdownInput() { end(); }
        @Override public void close() { closed = true; end(); }
        @Override public void abort() {
            closed = true;
            IOException failure = new IOException("Transport aborted");
            input.fail(failure);
            PendingOutput pending;
            while ((pending = pendingOutput.poll()) != null) pending.completion.completeExceptionally(failure);
            driver.inputAvailable();
        }
    }

    private static final class PendingOutput {
        final ByteBuffer bytes;
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        PendingOutput(ByteBuffer bytes) { this.bytes = bytes.duplicate(); }
    }
}
