package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.security.cert.Certificate;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

@Timeout(15)
class WebsocketReadDriverTest {
    @Test
    void readTimeoutReservesItsErrorBeforeAbortCanPublishAnotherTimeout() throws Exception {
        var error = new CompletableFuture<Throwable>();
        try (var fixture = new Fixture(new BaseWebSocket() {
            @Override public void onError(Throwable failure) { error.complete(failure); }
        }, 77)) {
            fixture.start();
            fixture.until(() -> fixture.timer.waits.size() == 1);
            // A connection deadline can race the reader's own abort. It must not replace
            // the read timeout that caused termination in the first place.
            fixture.abortHook = fixture.websocket::onTimeout;
            fixture.timer.waits.get(0).fire();
            fixture.until(fixture.driver.completion()::isDone);
            fixture.driver.completion().get();
            assertInstanceOf(SocketTimeoutException.class, error.get());
            assertEquals(WebsocketSessionState.TIMED_OUT, fixture.websocket.state());
        }
    }

    @Test
    void retirementWaitsForAnErrorCallbackFromALateTransportWriteFailure() throws Exception {
        var output = new ControlledOutput();
        var errorEntered = new CompletableFuture<Throwable>();
        var releaseError = new java.util.concurrent.CountDownLatch(1);
        var writeFinished = new CompletableFuture<Throwable>();
        try (var fixture = new Fixture(new BaseWebSocket() {
            @Override public void onClientClosed(int code, String reason) { }
            @Override public void onError(Throwable failure) throws Exception {
                errorEntered.complete(failure);
                releaseError.await();
            }
        }, 0, output)) {
            try {
                fixture.start();
                fixture.until(() -> fixture.websocket.state() == WebsocketSessionState.OPEN);
                fixture.websocket.sendBinary(ByteBuffer.wrap(new byte[]{42}), writeFinished::complete);
                output.next().completion.complete(null);
                var payload = output.next();
                fixture.offer(WebSocketWireTestSupport.frame(true, 8, new byte[]{3, (byte) 232}));
                fixture.until(() -> fixture.closes.get() == 1);
                fixture.barrier();
                assertFalse(fixture.driver.completion().isDone());
                IOException lateFailure = new IOException("Close deadline aborted the borrowed write");
                payload.completion.completeExceptionally(lateFailure);
                assertSame(lateFailure, errorEntered.get(2, TimeUnit.SECONDS));
                fixture.internal.submit(() -> {}).get(2, TimeUnit.SECONDS);
                assertFalse(fixture.driver.completion().isDone(), "An executing error callback still belongs to the exchange");
                releaseError.countDown();
                fixture.driver.completion().get(2, TimeUnit.SECONDS);
                assertSame(lateFailure, writeFinished.get(2, TimeUnit.SECONDS));
                assertEquals(WebsocketSessionState.ERRORED, fixture.websocket.state());
            } finally { releaseError.countDown(); }
        }
    }

    @ParameterizedTest @ValueSource(ints = {8, 9})
    void defaultControlRepliesSuspendWithoutHoldingTheApplicationWorker(int opcode) throws Exception {
        var output = new ControlledOutput();
        try (var fixture = new Fixture(new BaseWebSocket() {}, 0, output)) {
            fixture.start();
            byte[] payload = new byte[]{3, (byte) 232};
            fixture.offer(WebSocketWireTestSupport.frame(true, opcode, payload));
            var header = output.next();
            assertEquals(ByteBuffer.wrap(new byte[]{(byte) (opcode == 8 ? 0x88 : 0x8a), 2}), header.bytes);
            fixture.barrier(); // Both executors must remain available while the control reply is blocked.
            assertFalse(fixture.driver.completion().isDone());
            header.completion.complete(null);
            var body = output.next();
            assertEquals(ByteBuffer.wrap(payload), body.bytes);
            fixture.barrier();
            body.completion.complete(null);
            if (opcode == 8) {
                fixture.driver.completion().get(2, TimeUnit.SECONDS);
                assertEquals(WebsocketSessionState.CLIENT_CLOSED, fixture.websocket.state());
            } else {
                fixture.barrier();
                assertEquals(WebsocketSessionState.OPEN, fixture.websocket.state());
            }
        }
    }

    @Test
    void unexpectedWriteCallbackExecutorFailureClosesTheSessionAndDoesNotStrandItsMailbox() throws Exception {
        var output = new ControlledOutput();
        try (var fixture = new Fixture(new BaseWebSocket() {}, 0, output)) {
            fixture.start();
            fixture.until(() -> fixture.websocket.state() == WebsocketSessionState.OPEN);
            fixture.barrier();
            fixture.application.failure = new IllegalArgumentException("Executor failed to submit callback");
            fixture.websocket.sendBinary(ByteBuffer.allocate(0), error -> fail("Rejected callback ran"));
            output.next().completion.complete(null);
            fixture.until(() -> fixture.aborts.get() > 0);
            fixture.until(fixture.driver.completion()::isDone);
            assertEquals(WebsocketSessionState.ERRORED, fixture.websocket.state());
            assertTrue(fixture.driver.completion().isCompletedExceptionally());
        }
    }

    @Test
    void peerCloseWaitsForTheServerClosePayloadWithoutHoldingTheInternalWorker() throws Exception {
        var output = new ControlledOutput();
        var peerClosed = new CompletableFuture<Void>();
        try (var fixture = new Fixture(new BaseWebSocket() {
            @Override public void onClientClosed(int code, String reason) { peerClosed.complete(null); }
        }, 0, output)) {
            fixture.start();
            fixture.until(() -> fixture.websocket.state() == WebsocketSessionState.OPEN);
            var closer = Executors.newSingleThreadExecutor();
            try {
                var close = closer.submit(() -> { fixture.websocket.close(1000, "bye"); return null; });
                var header = output.next();
                assertEquals(ByteBuffer.wrap(new byte[]{(byte) 0x88, 5}), header.bytes);
                fixture.offer(WebSocketWireTestSupport.frame(true, 8, new byte[]{3, (byte) 232}));
                peerClosed.get(2, TimeUnit.SECONDS);
                fixture.barrier();
                assertFalse(fixture.driver.completion().isDone());
                assertFalse(fixture.websocket.closeSent());
                header.completion.complete(null);
                var payload = output.next();
                fixture.barrier();
                assertFalse(fixture.driver.completion().isDone());
                payload.completion.complete(null);
                close.get(2, TimeUnit.SECONDS);
                fixture.driver.completion().get(2, TimeUnit.SECONDS);
                assertTrue(fixture.websocket.closeSent());
                assertEquals(WebsocketSessionState.SERVER_CLOSED, fixture.websocket.state());
                assertEquals(1, fixture.closes.get());
                assertEquals(2, output.writes.size());
            } finally { output.fail(); closer.shutdownNow(); }
        }
    }

    @Test
    void protocolErrorCloseSuspendsUntilOutputDrainsBeforeReportingTheInvalidFrame() throws Exception {
        var output = new ControlledOutput();
        var error = new CompletableFuture<Throwable>();
        try (var fixture = new Fixture(new BaseWebSocket() {
            @Override public void onError(Throwable failure) { error.complete(failure); }
        }, 0, output)) {
            fixture.start();
            fixture.offer(new byte[]{(byte) 0x81, 0}); // Client frames must be masked.
            var header = output.next();
            fixture.barrier();
            assertFalse(error.isDone());
            header.completion.complete(null);
            var payload = output.next();
            assertEquals(1002, payload.bytes.getShort(0));
            fixture.barrier();
            assertFalse(error.isDone());
            payload.completion.complete(null);
            fixture.driver.completion().get(2, TimeUnit.SECONDS);
            assertInstanceOf(WebsocketFrameDecoder.InvalidFrame.class, error.get());
            assertEquals(WebsocketSessionState.ERRORED, fixture.websocket.state());
        }
    }

    @Test
    void interruptedBlockingSendRetainsItsBorrowUntilTransportAcknowledgesAbort() throws Exception {
        var output = new ControlledOutput();
        var writeReturned = new CompletableFuture<Throwable>();
        var interruptedOnReturn = new CompletableFuture<Boolean>();
        try (var fixture = new Fixture(new BaseWebSocket() {
            @Override public void onError(Throwable failure) { }
        }, 0, output)) {
            fixture.start();
            fixture.until(() -> fixture.websocket.state() == WebsocketSessionState.OPEN);
            ByteBuffer source = ByteBuffer.allocateDirect(1024).putInt(42).flip();
            Thread caller = new Thread(() -> {
                try { fixture.websocket.sendBinary(source); writeReturned.complete(null); }
                catch (Throwable failure) { writeReturned.complete(failure); }
                finally { interruptedOnReturn.complete(Thread.currentThread().isInterrupted()); }
            }, "blocking-websocket-caller");
            try {
                caller.start();
                output.next().completion.complete(null);
                var payload = output.next();
                caller.interrupt();
                fixture.until(() -> fixture.aborts.get() > 0);
                fixture.driver.inputAvailable(); // The real transport owner supplies this failure notification.
                fixture.barrier();
                assertFalse(writeReturned.isDone(), "The caller cannot reuse storage while output still borrows it");
                assertFalse(fixture.driver.completion().isDone());
                assertEquals(42, payload.bytes.getInt(0));
                payload.completion.completeExceptionally(new IOException("Abort acknowledged"));
                assertInstanceOf(java.io.InterruptedIOException.class, writeReturned.get(2, TimeUnit.SECONDS));
                assertTrue(interruptedOnReturn.get(2, TimeUnit.SECONDS));
                fixture.driver.completion().get(2, TimeUnit.SECONDS);
                assertEquals(0, source.position());
            } finally { output.fail(); caller.interrupt(); caller.join(2000); }
        }
    }

    @Test
    void readTimeoutTracksPartialInputButPausesForDeferredCallbacksAndIgnoresOldTimers() throws Exception {
        var acknowledgement = new CompletableFuture<DoneCallback>();
        var error = new CompletableFuture<Throwable>();
        try (var fixture = new Fixture(new BaseWebSocket() {
            @Override public void onBinary(ByteBuffer data, boolean last, DoneCallback done) {
                acknowledgement.complete(done);
            }
            @Override public void onError(Throwable failure) { error.complete(failure); }
        }, 77)) {
            try {
                fixture.start();
                fixture.until(() -> fixture.timer.waits.size() == 1);
                Wait first = fixture.timer.waits.get(0);
                byte[] frame = WebSocketWireTestSupport.frame(true, 2, new byte[]{1, 2, 3});
                fixture.offer(java.util.Arrays.copyOf(frame, 1));
                fixture.until(() -> fixture.timer.waits.size() == 2);
                assertTrue(first.future.isCancelled());
                first.fire(); // Already executing when cancellation occurred: an old generation must be harmless.
                fixture.barrier();
                assertFalse(error.isDone());
                Wait second = fixture.timer.waits.get(1);
                fixture.offer(java.util.Arrays.copyOfRange(frame, 1, frame.length));
                fixture.until(acknowledgement::isDone);
                fixture.barrier();
                assertTrue(second.future.isCancelled());
                second.fire();
                fixture.barrier();
                assertFalse(error.isDone());
                assertFalse(fixture.driver.completion().isDone());
                assertEquals(2, fixture.timer.waits.size(), "No read deadline runs while a callback owns the frame");
                acknowledgement.get().onComplete(null);
                fixture.until(() -> fixture.timer.waits.size() == 3);
                fixture.timer.waits.get(2).fire();
                fixture.until(fixture.driver.completion()::isDone);
                fixture.driver.completion().get();
                assertInstanceOf(SocketTimeoutException.class, error.get());
                assertEquals(WebsocketSessionState.TIMED_OUT, fixture.websocket.state());
                assertTrue(fixture.aborts.get() > 0);
            } finally {
                if (acknowledgement.isDone()) acknowledgement.get().onComplete(null);
            }
        }
    }

    @Test
    void readinessOwnerFailureRetainsTheOutstandingReceiveThenFinishesWithoutItsOwner() throws Exception {
        var acknowledgement = new CompletableFuture<DoneCallback>();
        var payload = new CompletableFuture<ByteBuffer>();
        var error = new CompletableFuture<Throwable>();
        try (var fixture = new Fixture(new BaseWebSocket() {
            @Override public void onBinary(ByteBuffer bytes, boolean last, DoneCallback done) {
                payload.complete(bytes);
                acknowledgement.complete(done);
            }
            @Override public void onError(Throwable failure) { error.complete(failure); }
        }, 0)) {
            try {
                fixture.start();
                fixture.offer(WebSocketWireTestSupport.frame(true, 2, new byte[]{1, 2, 3}));
                fixture.until(acknowledgement::isDone);
                IOException failedOwner = new IOException("readiness owner failed");
                fixture.driver.ownerFailed(failedOwner);
                fixture.barrier();
                assertFalse(fixture.driver.completion().isDone());
                assertFalse(error.isDone());
                assertEquals(ByteBuffer.wrap(new byte[]{1, 2, 3}), payload.get());
                acknowledgement.get().onComplete(null);
                fixture.driver.completion().get(3, TimeUnit.SECONDS);
                assertInstanceOf(ClientDisconnectedException.class, error.get());
                assertSame(failedOwner, error.get().getCause());
            } finally {
                if (acknowledgement.isDone()) acknowledgement.get().onComplete(null);
            }
        }
    }

    @Test
    void rejectedReaderSubmissionCleansUpOffTheNotifyingThread() throws Exception {
        var error = new CompletableFuture<Throwable>();
        var errorThread = new CompletableFuture<String>();
        try (var fixture = new Fixture(new BaseWebSocket() {
            @Override public void onError(Throwable failure) {
                errorThread.complete(Thread.currentThread().getName());
                error.complete(failure);
            }
        }, 77)) {
            fixture.start();
            fixture.until(() -> fixture.timer.waits.size() == 1);
            fixture.internal.shutdown();
            assertTrue(fixture.internal.awaitTermination(2, TimeUnit.SECONDS));
            fixture.offer(WebSocketWireTestSupport.frame(true, 2, new byte[0]));
            fixture.driver.completion().get(3, TimeUnit.SECONDS);
            assertInstanceOf(RejectedExecutionException.class, error.get());
            assertEquals("websocket-driver-application", errorThread.get());
            assertEquals(WebsocketSessionState.ERRORED, fixture.websocket.state());
        }
    }

    @Test
    void writeCallbackRejectionRetainsExecutingApplicationCodeThenCancelsItsDeferredWait() throws Exception {
        var entered = new CompletableFuture<ByteBuffer>();
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var fixture = new Fixture(new BaseWebSocket() {
            @Override public void onBinary(ByteBuffer bytes, boolean last, DoneCallback done) throws Exception {
                entered.complete(bytes);
                release.await();
                // Completion would normally be chained to the write callback that was rejected.
            }
        }, 0)) {
            try {
                fixture.start();
                fixture.offer(WebSocketWireTestSupport.frame(true, 2, new byte[]{1, 2, 3}));
                fixture.until(entered::isDone);
                fixture.application.shutdown();
                fixture.websocket.dispatchWriteCallback(error -> fail("Rejected callback ran"), null);
                fixture.internal.submit(() -> {}).get(2, TimeUnit.SECONDS);
                assertEquals(WebsocketSessionState.ERRORED, fixture.websocket.state());
                assertFalse(fixture.driver.completion().isDone());
                assertEquals(ByteBuffer.wrap(new byte[]{1, 2, 3}), entered.get());
                release.countDown();
                fixture.until(fixture.driver.completion()::isDone);
                assertTrue(fixture.driver.completion().isCompletedExceptionally());
                assertEquals(ByteBuffer.wrap(new byte[]{1, 2, 3}), entered.get());
            } finally { release.countDown(); }
        }
    }

    @Test
    void rejectedConnectCallbackTerminatesRatherThanStrandingTheReader() throws Exception {
        try (var fixture = new Fixture(new BaseWebSocket() {
            @Override public void onConnect(MuWebSocketSession session) { fail("Rejected connect callback ran"); }
        }, 0)) {
            fixture.application.shutdown();
            fixture.start();
            fixture.until(fixture.driver.completion()::isDone);
            assertTrue(fixture.driver.completion().isCompletedExceptionally());
            assertEquals(WebsocketSessionState.ERRORED, fixture.websocket.state());
        }
    }

    @Test
    void unexpectedApplicationExecutorFailureDoesNotStrandTheQueuedEvent() throws Exception {
        var failure = new IllegalStateException("application executor failed before dispatch");
        try (var fixture = new Fixture(new BaseWebSocket() { }, 77)) {
            fixture.start();
            fixture.until(() -> fixture.timer.waits.size() == 1);
            fixture.application.failure = failure;
            fixture.offer(WebSocketWireTestSupport.frame(true, 2, new byte[0]));
            fixture.until(fixture.driver.completion()::isDone);
            assertTrue(fixture.driver.completion().isCompletedExceptionally());
            assertEquals(WebsocketSessionState.ERRORED, fixture.websocket.state());
        }
    }

    @Test
    void transportFailuresKeepTimeoutsDistinctFromDisconnects() throws Exception {
        for (IOException failure : new IOException[]{new IOException("transport failed"),
            new SocketTimeoutException("read deadline"), new java.io.InterruptedIOException("read interrupted")}) {
            var error = new CompletableFuture<Throwable>();
            try (var fixture = new Fixture(new BaseWebSocket() {
                @Override public void onError(Throwable cause) { error.complete(cause); }
            }, 0)) {
                fixture.start();
                fixture.barrier();
                fixture.input.fail(failure);
                fixture.driver.inputAvailable();
                fixture.until(fixture.driver.completion()::isDone);
                fixture.driver.completion().get();
                if (failure instanceof java.io.InterruptedIOException) assertSame(failure, error.get());
                else {
                    assertInstanceOf(ClientDisconnectedException.class, error.get());
                    assertSame(failure, error.get().getCause());
                }
                assertEquals(failure instanceof SocketTimeoutException ? WebsocketSessionState.TIMED_OUT
                    : WebsocketSessionState.ERRORED, fixture.websocket.state());
            }
        }
    }

    @Test
    void receiveFailureIsReportedOnceAndAnErrorCallbackFailureStillCompletesTheReader() throws Exception {
        var received = new IllegalArgumentException("application receive failure");
        var reporting = new IllegalStateException("application error callback failure");
        var errors = new AtomicInteger();
        try (var fixture = new Fixture(new BaseWebSocket() {
            @Override public void onBinary(ByteBuffer bytes, boolean last, DoneCallback done) throws Exception { done.onComplete(received); }
            @Override public void onError(Throwable failure) {
                assertSame(received, failure);
                errors.incrementAndGet();
                throw reporting;
            }
        }, 0)) {
            fixture.start();
            fixture.offer(WebSocketWireTestSupport.frame(true, 2, new byte[0]));
            fixture.until(fixture.driver.completion()::isDone);
            var failed = assertThrows(java.util.concurrent.ExecutionException.class, () -> fixture.driver.completion().get());
            assertSame(reporting, failed.getCause().getCause());
            assertEquals(1, errors.get());
            assertEquals(WebsocketSessionState.ERRORED, fixture.websocket.state());
        }
    }

    private static final class Wait {
        final Runnable callback;
        final ScheduledFuture<?> future;
        Wait(Runnable callback, ScheduledFuture<?> future) { this.callback = callback; this.future = future; }
        void fire() { callback.run(); }
    }

    private static final class Timer extends ScheduledThreadPoolExecutor {
        final List<Wait> waits = new CopyOnWriteArrayList<>();
        Timer() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            if (unit.toMillis(delay) != 77) return super.schedule(task, delay, unit);
            ScheduledFuture<?> future = super.schedule(() -> { }, 1, TimeUnit.DAYS);
            waits.add(new Wait(task, future));
            return future;
        }
    }

    private static final class ApplicationExecutor extends java.util.concurrent.ThreadPoolExecutor {
        volatile RuntimeException failure;
        ApplicationExecutor() {
            super(1, 1, 0, TimeUnit.SECONDS, new java.util.concurrent.LinkedBlockingQueue<>(),
                r -> new Thread(r, "websocket-driver-application"));
        }
        @Override public void execute(Runnable task) {
            RuntimeException failed = failure;
            if (failed != null) throw failed;
            super.execute(task);
        }
    }

    private static final class ControlledOutput implements AsyncTransportOutput {
        final List<PendingWrite> writes = new CopyOnWriteArrayList<>();
        final java.util.concurrent.BlockingQueue<PendingWrite> pending = new java.util.concurrent.LinkedBlockingQueue<>();
        @Override public CompletableFuture<@Nullable Void> write(ByteBuffer bytes) {
            var write = new PendingWrite(bytes.duplicate());
            writes.add(write);
            pending.add(write);
            return write.completion;
        }
        PendingWrite next() throws Exception { return java.util.Objects.requireNonNull(pending.poll(2, TimeUnit.SECONDS)); }
        void fail() { for (var write : writes) write.completion.completeExceptionally(new IOException("fixture closed")); }
    }

    private static final class PendingWrite {
        final ByteBuffer bytes;
        final CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
        PendingWrite(ByteBuffer bytes) { this.bytes = bytes; }
    }

    private static final class Fixture implements AutoCloseable {
        final ExecutorService internal = Executors.newSingleThreadExecutor(r -> new Thread(r, "websocket-driver-internal"));
        final ApplicationExecutor application = new ApplicationExecutor();
        final Timer timer = new Timer();
        final Semaphore changed = new Semaphore(0);
        final TransportInputBuffer input = new TransportInputBuffer(8192, changed::release);
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final AtomicInteger aborts = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        Runnable abortHook = () -> { };
        final ControlledOutput asyncOutput;
        final MuServer server;
        final Http1Connection connection;
        final WebsocketConnection websocket;
        final WebsocketConnection.ReadDriver driver;

        Fixture(MuWebSocket handler, int timeoutMillis) throws Exception {
            this(handler, timeoutMillis, null);
        }

        Fixture(MuWebSocket handler, int timeoutMillis, ControlledOutput asyncOutput) throws Exception {
            this.asyncOutput = asyncOutput;
            var captured = new AtomicReference<BaseHttpConnection>();
            var builder = MuServerBuilder.httpServer().withHandlerExecutor(application)
                .addHandler((req, res) -> { captured.set((BaseHttpConnection) req.connection()); res.write("ready"); return true; });
            builder.executionResourcesFactory = (supplied, mode) -> new ExecutionResources(application, false, internal, timer);
            server = builder.start();
            try (var response = call(request(server.uri()).header("Connection", "close"))) {
                assertEquals("ready", response.body().string());
            }
            BaseHttpConnection config = captured.get();
            connection = new Http1Connection(config.server, config.creator, transport(), ConnectionAcceptedTime.now(), null, application);
            websocket = new WebsocketConnection(connection, handler, new WebSocketHandlerBuilder.Settings(0, 1024, 1024, timeoutMillis));
            driver = websocket.readDriver(input, output, ByteBuffer.allocate(8192).flip(), asyncOutput);
            barrier();
        }

        void start() { driver.inputAvailable(); }
        void offer(byte[] bytes) throws IOException {
            assertEquals(bytes.length, input.offer(ByteBuffer.wrap(bytes)));
            driver.inputAvailable();
        }
        void barrier() throws Exception {
            internal.submit(() -> {}).get(2, TimeUnit.SECONDS);
            application.submit(() -> {}).get(2, TimeUnit.SECONDS);
            internal.submit(() -> {}).get(2, TimeUnit.SECONDS);
        }
        void until(BooleanSupplier complete) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!complete.getAsBoolean()) {
                assertTrue(System.nanoTime() < deadline, "WebSocket driver did not make progress");
                driver.inputAvailable();
                changed.tryAcquire(1, TimeUnit.MILLISECONDS);
            }
        }
        @Override public void close() throws Exception {
            try {
                if (asyncOutput != null) asyncOutput.fail();
                driver.ownerFailed(new IOException("fixture closed"));
                until(driver.completion()::isDone);
            } finally {
                server.stop();
                internal.shutdownNow();
                application.shutdownNow();
                timer.shutdownNow();
                assertTrue(internal.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(application.awaitTermination(2, TimeUnit.SECONDS));
                assertTrue(timer.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
        private ConnectionTransport transport() {
            return new ConnectionTransport() {
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
                    aborts.incrementAndGet(); input.fail(new IOException("Transport aborted")); abortHook.run();
                }
                @Override public void close() { closes.incrementAndGet(); input.endOfInput(); }
            };
        }
    }
}
