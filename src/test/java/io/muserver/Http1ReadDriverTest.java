package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

@Timeout(20)
class Http1ReadDriverTest {
    @Test
    void shutdownPreventsAQueuedExchangeFromEnteringItsHandler() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var fixture = new Fixture((request, response) -> { handled.incrementAndGet(); return true; })) {
            Session session = fixture.add(512);
            fixture.internal.submit(() -> {
                entered.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
                return null;
            });
            try {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                session.offer("GET /queued HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
                assertTrue(session.driver.advance());
                session.connection.forceShutdown();
            } finally { release.countDown(); }
            fixture.until(session.driver.completion()::isDone);
            session.driver.completion().get();
            assertEquals(0, handled.get());
            assertEquals(0, session.connection.completedRequests());
        } finally { release.countDown(); }
    }

    @Test
    void handlerExecutorRejectionSends503AndRetiresTheRequest() throws Exception {
        try (var fixture = new Fixture((request, response) -> { fail("Rejected handler ran"); return true; })) {
            Session session = fixture.add(512);
            fixture.application.shutdown();
            session.offer("GET /rejected HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
            fixture.until(session.driver.completion()::isDone);
            session.driver.completion().get();
            assertTrue(session.sink.bytes.toString(US_ASCII).startsWith("HTTP/1.1 503 "));
            assertEquals(1, session.connection.rejectedDueToOverload());
            assertEquals(0, session.connection.completedRequests());
            assertTrue(fixture.server.stats().activeRequests().isEmpty());
        }
    }

    @Test
    void idleKeepalivesAndSuspendedAsyncRequestsDoNotOccupyEitherWorker() throws Exception {
        Queue<AsyncHandle> handles = new ConcurrentLinkedQueue<>();
        AtomicInteger handled = new AtomicInteger();
        try (var fixture = new Fixture((request, response) -> {
            handles.add(request.handleAsync());
            handled.incrementAndGet();
            return true;
        })) {
            for (int i = 0; i < 128; i++) fixture.add(512);
            for (Session session : fixture.sessions) assertFalse(session.driver.advance());
            fixture.barrier();
            for (int round = 1; round <= 2; round++) {
                for (Session session : fixture.sessions) session.offer(("GET /async HTTP/1.1\r\nHost: localhost\r\n"
                    + (round == 2 ? "Connection: close\r\n" : "") + "\r\n").getBytes(US_ASCII));
                int expected = round * fixture.sessions.size();
                fixture.until(() -> handled.get() == expected);
                fixture.barrier(); // Both single-thread executors remain available with 128 suspended requests.
                for (Session session : fixture.sessions) assertFalse(session.driver.completion().isDone());
                AsyncHandle handle;
                while ((handle = handles.poll()) != null) {
                    handle.write(ByteBuffer.wrap("ok".getBytes(US_ASCII)));
                    handle.complete();
                }
                int completed = round;
                fixture.until(() -> fixture.sessions.stream().allMatch(session -> session.connection.completedRequests() == completed));
                fixture.tick();
                fixture.barrier();
            }
            fixture.until(() -> fixture.sessions.stream().allMatch(session -> session.driver.completion().isDone()));
            for (Session session : fixture.sessions) {
                session.driver.completion().get();
                assertTrue(session.connection.activeRequests().isEmpty());
                assertEquals(2, session.sink.bytes.toString(US_ASCII).split("HTTP/1.1 200 OK", -1).length - 1);
            }
            assertEquals(1, fixture.internalThreads.get());
            assertEquals(1, fixture.applicationThreads.get());
        }
    }

    @Test
    void idleWebsocketsAndDeferredReceivesReleaseBothWorkersAndRetainFramesUntilAcknowledged() throws Exception {
        AtomicInteger connected = new AtomicInteger();
        AtomicInteger pings = new AtomicInteger();
        Queue<DoneCallback> acknowledgements = new ConcurrentLinkedQueue<>();
        Queue<ByteBuffer> retained = new ConcurrentLinkedQueue<>();
        try (var fixture = new Fixture(webSocketHandler((request, headers) -> new BaseWebSocket() {
            @Override public void onConnect(MuWebSocketSession session) throws Exception {
                super.onConnect(session);
                connected.incrementAndGet();
            }
            @Override public void onBinary(ByteBuffer bytes, boolean last, DoneCallback done) {
                retained.add(bytes);
                acknowledgements.add(done);
            }
            @Override public void onPing(ByteBuffer bytes) { pings.incrementAndGet(); }
        }).withPingInterval(0, TimeUnit.MILLISECONDS).build())) {
            byte[] upgrade = ("GET /socket HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(US_ASCII);
            for (int i = 0; i < 128; i++) fixture.add(512).offer(upgrade);
            fixture.until(() -> connected.get() == 128);
            fixture.barrier();
            byte[] frame = WebSocketWireTestSupport.frame(true, 2, new byte[]{1, 2, 3});
            for (Session session : fixture.sessions) {
                session.offer(java.util.Arrays.copyOf(frame, 1));
            }
            fixture.tick();
            fixture.barrier(); // Every connection has only the first byte of its next frame.
            for (Session session : fixture.sessions) {
                session.offer(java.util.Arrays.copyOfRange(frame, 1, frame.length));
                session.offer(WebSocketWireTestSupport.frame(true, 9, new byte[]{4}));
            }
            fixture.until(() -> acknowledgements.size() == 128);
            fixture.barrier();
            assertEquals(0, pings.get());
            Session cancelled = fixture.sessions.get(0);
            cancelled.connection.forceShutdown();
            fixture.tick();
            fixture.barrier();
            assertFalse(cancelled.driver.completion().isDone(), "An outstanding receive still owns its frame");
            assertEquals(1, cancelled.connection.activeWebsockets().size());
            for (ByteBuffer bytes : retained) {
                assertEquals(ByteBuffer.wrap(new byte[]{1, 2, 3}), bytes);
            }
            DoneCallback done;
            while ((done = acknowledgements.poll()) != null) done.onComplete(null);
            fixture.until(() -> pings.get() == 127 && cancelled.driver.completion().isDone());
            fixture.barrier();
            assertEquals(1, fixture.internalThreads.get());
            assertEquals(1, fixture.applicationThreads.get());
        } finally {
            DoneCallback done;
            while ((done = acknowledgements.poll()) != null) done.onComplete(null);
        }
    }

    @Test
    void forcedShutdownCompletesAnAsyncRequestThatHasNoPendingIo() throws Exception {
        CompletableFuture<AsyncHandle> suspended = new CompletableFuture<>();
        CompletableFuture<ResponseInfo> completed = new CompletableFuture<>();
        try (var fixture = new Fixture((request, response) -> {
            response.addCompletionListener(completed::complete);
            suspended.complete(request.handleAsync());
            return true;
        })) {
            Session session = fixture.add(512);
            session.offer("GET /async HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
            fixture.until(suspended::isDone);
            fixture.barrier();
            session.connection.forceShutdown();
            fixture.until(session.driver.completion()::isDone);
            session.driver.completion().get();
            assertFalse(completed.get(2, TimeUnit.SECONDS).completedSuccessfully());
            assertTrue(session.connection.activeRequests().isEmpty());
            assertEquals(1, session.connection.completedRequests());
        }
    }

    @Test
    void rejectedCleanupStillRetiresTheExchangeAndReleasesAdmission() throws Exception {
        CompletableFuture<AsyncHandle> suspended = new CompletableFuture<>();
        AtomicReference<Mu3Request> accepted = new AtomicReference<>();
        try (var fixture = new Fixture((request, response) -> {
            accepted.set((Mu3Request) request);
            suspended.complete(request.handleAsync());
            return true;
        }, 1)) {
            Session session = fixture.add(512);
            session.offer("GET /async HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
            fixture.until(suspended::isDone);
            fixture.barrier();
            assertFalse(fixture.config.server.tryAdmit(accepted.get()));
            fixture.internal.shutdown();
            suspended.get().complete();
            fixture.until(session.driver.completion()::isDone);
            assertTrue(session.driver.completion().isCompletedExceptionally());
            assertTrue(session.connection.activeRequests().isEmpty());
            assertEquals(1, session.connection.completedRequests());
            assertTrue(fixture.config.server.tryAdmit(accepted.get()));
            accepted.get().releaseAdmission();
        }
    }

    @Test
    void shutdownBetweenUpgradeResponseAndTakeoverDoesNotStartTheWebsocket() throws Exception {
        AtomicInteger connected = new AtomicInteger();
        try (var fixture = new Fixture(webSocketHandler((request, headers) -> new BaseWebSocket() {
            @Override public void onConnect(MuWebSocketSession session) { connected.incrementAndGet(); }
        }).withPingInterval(0, TimeUnit.MILLISECONDS).build())) {
            Session session = fixture.add(512);
            session.offer(("GET /socket HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(US_ASCII));
            assertTrue(session.driver.advance());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (session.connection.completedRequests() == 0) {
                assertTrue(System.nanoTime() < deadline);
                session.output.drainTo(session.sink, 7);
                fixture.changed.tryAcquire(1, TimeUnit.MILLISECONDS);
            }
            fixture.barrier();
            session.connection.forceShutdown();
            fixture.until(session.driver.completion()::isDone);
            session.driver.completion().get();
            assertEquals(0, connected.get());
        }
    }

    @Test
    void upgradeTransfersTheUnreadParserRangeToWebsocketOwnership() throws Exception {
        List<String> messages = new ArrayList<>();
        try (var fixture = new Fixture(webSocketHandler((request, headers) -> new SimpleWebSocket() {
            @Override public void onText(String message) { messages.add(message); }
            @Override public void onBinary(ByteBuffer message) { fail("Expected text"); }
        }).withPingInterval(0, TimeUnit.MILLISECONDS).build())) {
            Session session = fixture.add(8192);
            var wire = new ByteArrayOutputStream();
            wire.write(("GET /socket HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(US_ASCII));
            wire.write(WebSocketWireTestSupport.frame(true, 1, "prefetched".getBytes(US_ASCII)));
            wire.write(WebSocketWireTestSupport.frame(true, 8, new byte[]{3, (byte) 232}));
            // Ownership regression fixture: all bytes arrive in the parser's first source read.
            // Network clients should continue to wait for the 101 response before sending frames.
            session.offer(wire.toByteArray());
            fixture.until(session.driver.completion()::isDone);
            session.driver.completion().get();
            assertEquals(List.of("prefetched"), messages);
            assertTrue(session.sink.bytes.toString(US_ASCII).startsWith("HTTP/1.1 101 "));
            byte[] response = session.sink.bytes.toByteArray();
            assertArrayEquals(new byte[]{(byte) 0x88, 2, 3, (byte) 232},
                java.util.Arrays.copyOfRange(response, response.length - 4, response.length));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final AtomicInteger internalThreads = new AtomicInteger();
        final AtomicInteger applicationThreads = new AtomicInteger();
        final ExecutorService internal = Executors.newSingleThreadExecutor(task -> new Thread(task, "read-driver-internal-" + internalThreads.incrementAndGet()));
        final ExecutorService application = Executors.newSingleThreadExecutor(task -> new Thread(task, "read-driver-application-" + applicationThreads.incrementAndGet()));
        final Semaphore changed = new Semaphore(0);
        final List<Session> sessions = new ArrayList<>();
        final MuServer server;
        final BaseHttpConnection config;

        Fixture(MuHandler handler) throws Exception { this(handler, 0); }

        Fixture(MuHandler handler, int maxRequests) throws Exception {
            AtomicReference<BaseHttpConnection> captured = new AtomicReference<>();
            server = TestExecutionResources.configure(MuServerBuilder.httpServer(), null, null, internal, null)
                .withHandlerExecutor(application).withMaxConcurrentRequests(maxRequests)
                .addHandler((request, response) -> {
                    if (request.uri().getPath().equals("/fixture")) {
                        captured.set((BaseHttpConnection) request.connection());
                        response.write("ready");
                        return true;
                    }
                    return handler.handle(request, response);
                }).start();
            try (var response = call(request(server.uri().resolve("/fixture")).header("Connection", "close"))) {
                assertEquals("ready", response.body().string());
            }
            config = captured.get();
            barrier();
        }

        Session add(int inputCapacity) {
            Session session = new Session(this, inputCapacity);
            sessions.add(session);
            return session;
        }

        void barrier() throws Exception {
            assertEquals(42, internal.submit(() -> 42).get(2, TimeUnit.SECONDS));
            assertEquals(42, application.submit(() -> 42).get(2, TimeUnit.SECONDS));
        }

        void tick() throws IOException {
            for (Session session : sessions) {
                for (int i = 0; i < 8 && session.driver.advance(); i++) { }
                if (session.output.pendingBytes() > 0) session.output.drainTo(session.sink, 7);
            }
        }

        void until(BooleanSupplier done) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (!done.getAsBoolean()) {
                assertTrue(System.nanoTime() < deadline, "Readiness driver did not make progress");
                tick();
                changed.tryAcquire(1, TimeUnit.MILLISECONDS);
            }
        }

        @Override public void close() throws Exception {
            for (Session session : sessions) session.connection.forceShutdown();
            try { until(() -> sessions.stream().allMatch(session -> session.driver.completion().isDone())); }
            finally {
                server.stop();
                internal.shutdownNow();
                application.shutdownNow();
                assertTrue(internal.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(application.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    private static final class Session implements ConnectionTransport {
        final TransportInputBuffer input;
        final TransportOutputBuffer output;
        final TransportOutputBufferTest.Sink sink = new TransportOutputBufferTest.Sink(7);
        final Http1Connection connection;
        final Http1Connection.ReadDriver driver;

        Session(Fixture fixture, int inputCapacity) {
            input = new TransportInputBuffer(inputCapacity, fixture.changed::release);
            output = new TransportOutputBuffer(97, fixture.changed::release);
            connection = new Http1Connection(fixture.config.server, fixture.config.creator, this,
                ConnectionAcceptedTime.now(), null, fixture.application);
            driver = connection.readDriver(input, new HttpConnectionOutputStream(connection, output), fixture.changed::release);
        }

        void offer(byte[] bytes) throws IOException {
            assertEquals(bytes.length, input.offer(ByteBuffer.wrap(bytes)));
            connection.onBytesRead(bytes.length);
        }

        @Override public InetSocketAddress remoteAddress() { return new InetSocketAddress("192.0.2.1", 1234); }
        @Override public InetSocketAddress localAddress() { return new InetSocketAddress("127.0.0.1", 80); }
        @Override public boolean isSecure() { return false; }
        @Override public String tlsProtocol() { return null; }
        @Override public String cipherSuite() { return null; }
        @Override public String sniHostName() { return null; }
        @Override public Certificate clientCertificate() { return null; }
        @Override public void readTimeoutMillis(int timeoutMillis) { input.readTimeoutMillis(timeoutMillis); }
        @Override public void shutdownInput() { input.endOfInput(); }
        @Override public void abort() {
            IOException failure = new IOException("Transport aborted");
            input.fail(failure);
            output.fail(failure);
        }
        @Override public void close() throws IOException {
            output.close();
            input.endOfInput();
        }
    }
}
