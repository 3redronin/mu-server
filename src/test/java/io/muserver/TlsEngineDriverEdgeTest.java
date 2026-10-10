package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.*;
import static javax.net.ssl.SSLEngineResult.Status.*;
import static org.junit.jupiter.api.Assertions.*;

/** Script provider outcomes that are difficult to force reliably with a real TLS peer. */
@Timeout(10)
class TlsEngineDriverEdgeTest {
    @Test
    void outputOverflowGrowsEvenWhenTheProviderSizeHintDoesNotChange() throws Exception {
        var engine = new Engine(NEED_WRAP);
        engine.wraps.add((src, dst) -> {
            assertEquals(4, dst.remaining());
            return engine.result(BUFFER_OVERFLOW, NEED_WRAP, 0, 0);
        });
        engine.wraps.add((src, dst) -> {
            assertEquals(8, dst.remaining());
            dst.put(new byte[]{1, 2, 3});
            return engine.result(OK, FINISHED, 0, 3);
        });
        var driver = driver(engine);
        assertTrue(driver.advance());
        assertTrue(driver.advance());
        assertTrue(driver.handshakeComplete());
        assertEquals(3, driver.encryptedOutputSize());
    }

    @Test
    void inputUnderflowAndPlaintextOverflowPreserveUnconsumedEncryptedBytes() throws Exception {
        var engine = new Engine(NEED_UNWRAP);
        engine.unwraps.add((src, dst) -> {
            assertEquals(4, src.remaining());
            engine.packetSize = 8;
            return engine.result(BUFFER_UNDERFLOW, NEED_UNWRAP, 0, 0);
        });
        engine.unwraps.add((src, dst) -> {
            assertEquals(8, src.remaining());
            engine.applicationSize = 8;
            return engine.result(BUFFER_OVERFLOW, NEED_UNWRAP, 0, 0);
        });
        engine.unwraps.add((src, dst) -> {
            dst.put(src);
            return engine.result(OK, FINISHED, 8, 8);
        });
        var driver = driver(engine);
        driver.receive(ByteBuffer.wrap(new byte[]{1, 2, 3, 4}));
        assertTrue(driver.advance());
        assertFalse(driver.advance(), "Underflow must await new bytes instead of spinning");
        assertEquals(4, driver.inputCapacity());
        driver.receive(ByteBuffer.wrap(new byte[]{5, 6, 7, 8}));
        assertTrue(driver.advance());
        assertTrue(driver.advance());
        assertFalse(driver.advance(), "Plaintext must be drained before another unwrap");
        ByteBuffer plaintext = ByteBuffer.allocate(8);
        plaintext.limit(3);
        assertEquals(3, driver.readPlaintext(plaintext));
        plaintext.limit(8);
        assertEquals(5, driver.readPlaintext(plaintext));
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8}, plaintext.array());
    }

    @Test
    void bufferLimitsFailClosedInsteadOfGrowingWithoutBound() throws Exception {
        var engine = new Engine(NEED_WRAP);
        engine.wraps.add((src, dst) -> engine.result(BUFFER_OVERFLOW, NEED_WRAP, 0, 0));
        engine.wraps.add((src, dst) -> engine.result(BUFFER_OVERFLOW, NEED_WRAP, 0, 0));
        var driver = new TlsEngineDriver(engine, task -> fail("Unexpected task"), () -> { }, 8);
        assertTrue(driver.advance());
        SSLException failure = assertThrows(SSLException.class, driver::advance);
        assertSame(failure, assertThrows(SSLException.class, driver::advance));
        assertEquals(0, driver.inputCapacity());
        engine.packetSize = 9;
        assertThrows(SSLException.class, () -> new TlsEngineDriver(engine, task -> { }, () -> { }, 8));
    }

    @Test
    void missingTasksAndExecutorRejectionAreTerminalFailures() throws Exception {
        var missing = driver(new Engine(NEED_TASK));
        assertThrows(SSLException.class, missing::advance);
        var engine = new Engine(NEED_TASK);
        engine.delegates.add(() -> engine.handshake = NEED_WRAP);
        var rejection = new RejectedExecutionException("TLS task capacity exhausted");
        var rejected = new TlsEngineDriver(engine, task -> { throw rejection; }, () -> { });
        assertSame(rejection, assertThrows(SSLException.class, rejected::advance).getCause());
        assertTrue(rejected.advanceFailureClose());
        assertFalse(rejected.tasksPending());
        assertEquals(NEED_WRAP, engine.handshake);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingDelegatedTasksExcludeEngineAccessAndCannotDelayAbort(boolean abort) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Semaphore continuation = new Semaphore(0);
        var engine = new Engine(NEED_TASK);
        engine.delegates.add(() -> {
            engine.taskRunning.set(true);
            entered.countDown();
            try {
                assertTrue(release.await(3, TimeUnit.SECONDS));
                engine.handshake = NEED_WRAP;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            } finally { engine.taskRunning.set(false); }
        });
        var driver = new TlsEngineDriver(engine, executor, continuation::release);
        try {
            assertTrue(driver.advance());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            int calls = engine.calls;
            driver.receive(ByteBuffer.wrap(new byte[]{1}));
            driver.closeOutbound();
            for (int i = 0; i < 100; i++) assertFalse(driver.advance());
            assertEquals(calls, engine.calls);
            IOException cause = new IOException("Transport aborted");
            if (abort) {
                driver.abort(cause);
                assertSame(cause, assertThrows(IOException.class, driver::advance));
                assertEquals(calls, engine.calls);
            }
            release.countDown();
            assertTrue(continuation.tryAcquire(2, TimeUnit.SECONDS));
            if (abort) {
                assertSame(cause, assertThrows(IOException.class, driver::advance));
                assertEquals(calls, engine.calls);
            } else {
                assertTrue(driver.advance()); // Observe task completion, no engine access yet.
                assertTrue(driver.advance()); // Apply the close requested while the task was active.
                engine.wraps.add((src, dst) -> {
                    dst.put((byte) 42);
                    engine.outboundDone = true;
                    return engine.result(CLOSED, NOT_HANDSHAKING, 0, 1);
                });
                assertTrue(driver.advance());
                assertFalse(driver.outboundDone(), "Close-notify must reach the transport first");
                driver.drainEncryptedTo(new TlsEngineDriverTest.Channel(bytes -> { bytes.get(); return 1; }), 1);
                assertTrue(driver.outboundDone());
            }
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void taskFailureIsReportedOnTheOwnerAndSchedulesItsContinuation() throws Exception {
        var scheduled = new ArrayDeque<Runnable>();
        var engine = new Engine(NEED_TASK);
        IllegalStateException cause = new IllegalStateException("Trust manager failed");
        engine.delegates.add(() -> { throw cause; });
        Semaphore ready = new Semaphore(0);
        var driver = new TlsEngineDriver(engine, scheduled::add, ready::release);
        assertTrue(driver.advance());
        int calls = engine.calls;
        assertFalse(driver.advance());
        scheduled.remove().run();
        assertTrue(ready.tryAcquire());
        assertSame(cause, assertThrows(SSLException.class, driver::advance).getCause());
        assertEquals(calls, engine.calls);
    }

    @Test
    void postHandshakeTaskAndControlOutputGateApplicationWrites() throws Exception {
        var scheduled = new ArrayDeque<Runnable>();
        var engine = new Engine(NEED_WRAP);
        engine.wraps.add((src, dst) -> engine.result(OK, FINISHED, 0, 0));
        var driver = new TlsEngineDriver(engine, scheduled::add, () -> { });
        driver.advance();
        engine.unwraps.add((src, dst) -> {
            src.get();
            return engine.result(OK, NEED_TASK, 1, 0);
        });
        engine.delegates.add(() -> engine.handshake = NEED_WRAP);
        engine.wraps.add((src, dst) -> {
            assertFalse(src.hasRemaining());
            dst.put((byte) 77);
            return engine.result(OK, NOT_HANDSHAKING, 0, 1);
        });
        ByteBuffer application = ByteBuffer.wrap(new byte[]{3});
        driver.receive(ByteBuffer.wrap(new byte[]{1}));
        driver.advance();
        driver.advance();
        assertEquals(0, driver.writePlaintext(application));
        scheduled.remove().run();
        driver.advance();
        driver.advance();
        assertEquals(0, driver.writePlaintext(application));
        assertEquals(0, application.position());
        driver.drainEncryptedTo(new TlsEngineDriverTest.Channel(bytes -> { assertEquals(77, bytes.get()); return 1; }), 1);
        engine.wraps.add((src, dst) -> {
            dst.put(src.get());
            return engine.result(OK, NOT_HANDSHAKING, 1, 1);
        });
        driver.writePlaintext(application);
        driver.drainEncryptedTo(new TlsEngineDriverTest.Channel(bytes -> { assertEquals(3, bytes.get()); return 1; }), 1);
        assertEquals(1, driver.writePlaintext(application));
    }

    @Test
    void zeroProgressDoesNotSpinAndNewInputResumesUnwrap() throws Exception {
        var engine = new Engine(NEED_UNWRAP);
        engine.unwraps.add((src, dst) -> engine.result(OK, NEED_UNWRAP, 0, 0));
        engine.unwraps.add((src, dst) -> {
            assertEquals(2, src.remaining());
            src.position(src.limit());
            return engine.result(OK, FINISHED, 2, 0);
        });
        var driver = driver(engine);
        driver.receive(ByteBuffer.wrap(new byte[]{1}));
        for (int i = 0; i < 100; i++) assertFalse(driver.advance());
        assertEquals(1, engine.unwraps.size());
        driver.receive(ByteBuffer.wrap(new byte[]{2}));
        assertTrue(driver.advance());
        engine.wraps.add((src, dst) -> engine.result(OK, NOT_HANDSHAKING, 0, 0));
        ByteBuffer source = ByteBuffer.wrap(new byte[]{3});
        for (int i = 0; i < 100; i++) assertEquals(0, driver.writePlaintext(source));
        assertEquals(0, source.position());
        assertTrue(engine.wraps.isEmpty());
    }

    @Test
    void unwrapAgainDoesNotConsumeFreshNetworkBytes() throws Exception {
        var engine = new Engine(NEED_UNWRAP_AGAIN);
        engine.unwraps.add((src, dst) -> {
            assertFalse(src.hasRemaining());
            return engine.result(OK, FINISHED, 0, 0);
        });
        engine.unwraps.add((src, dst) -> {
            assertEquals(7, src.get());
            return engine.result(OK, NOT_HANDSHAKING, 1, 0);
        });
        var driver = driver(engine);
        driver.receive(ByteBuffer.wrap(new byte[]{7}));
        assertTrue(driver.advance());
        assertTrue(driver.advance());
    }

    @Test
    void failedCiphertextWriteDiscardsTheRemainingPacketAndCannotAcknowledgePlaintext() throws Exception {
        var engine = new Engine(NEED_WRAP);
        engine.wraps.add((src, dst) -> { dst.put(new byte[]{1, 2}); return engine.result(OK, FINISHED, 0, 2); });
        var driver = driver(engine);
        driver.advance();
        IOException cause = new IOException("Socket closed");
        assertSame(cause, assertThrows(IOException.class, () -> driver.drainEncryptedTo(
            new TlsEngineDriverTest.Channel(bytes -> { bytes.get(); throw cause; }), 1)));
        assertEquals(0, driver.encryptedOutputSize());
        assertSame(cause, assertThrows(IOException.class, () -> driver.writePlaintext(ByteBuffer.wrap(new byte[]{1}))));
    }

    @Test
    void consumingPlaintextWithoutARecordCannotProduceFalseWriteCompletion() throws Exception {
        var engine = new Engine(NEED_WRAP);
        engine.wraps.add((src, dst) -> engine.result(OK, FINISHED, 0, 0));
        engine.wraps.add((src, dst) -> { src.get(); return engine.result(OK, NOT_HANDSHAKING, 1, 0); });
        var driver = driver(engine);
        driver.advance();
        ByteBuffer source = ByteBuffer.wrap(new byte[]{1});
        assertThrows(SSLException.class, () -> driver.writePlaintext(source));
        assertEquals(0, source.position());
    }

    @Test
    void plaintextBeforeHandshakeAuthenticationIsNeverPublished() throws Exception {
        var engine = new Engine(NEED_UNWRAP);
        engine.unwraps.add((src, dst) -> {
            src.get(); dst.put((byte) 42);
            return engine.result(OK, NEED_TASK, 1, 1);
        });
        var driver = driver(engine);
        driver.receive(ByteBuffer.wrap(new byte[]{1}));
        assertThrows(SSLException.class, driver::advance);
        assertEquals(0, driver.plaintextSize());
    }

    @Test
    void failureClosePreservesPartlySentRecordBeforeSendingAnAlertAndNeverAcknowledgesApplicationData() throws Exception {
        var engine = new Engine(NEED_WRAP);
        engine.wraps.add((src, dst) -> engine.result(OK, FINISHED, 0, 0));
        engine.wraps.add((src, dst) -> {
            dst.put(src);
            return engine.result(OK, NOT_HANDSHAKING, 3, 3);
        });
        engine.unwraps.add((src, dst) -> { throw new SSLException("Peer authentication failed"); });
        var driver = driver(engine);
        driver.advance();
        ByteBuffer application = ByteBuffer.wrap(new byte[]{1, 2, 3});
        driver.writePlaintext(application);
        ByteBuffer wire = ByteBuffer.allocate(5);
        var channel = new TlsEngineDriverTest.Channel(bytes -> {
            int count = bytes.remaining(); wire.put(bytes); return count;
        });
        driver.drainEncryptedTo(channel, 1);
        driver.receive(ByteBuffer.wrap(new byte[]{9}));
        assertThrows(SSLException.class, driver::advance);
        assertFalse(driver.advanceFailureClose());
        driver.drainFailureTo(channel, 1);
        assertFalse(driver.advanceFailureClose());
        driver.drainFailureTo(channel, 1);
        assertTrue(driver.advanceFailureClose());
        engine.wraps.add((src, dst) -> {
            dst.put(new byte[]{77, 78}); engine.outboundDone = true;
            return engine.result(CLOSED, NOT_HANDSHAKING, 0, 2);
        });
        assertTrue(driver.advanceFailureClose());
        driver.drainFailureTo(channel, 1);
        assertFalse(driver.failureCloseDone());
        driver.drainFailureTo(channel, 1);
        assertTrue(driver.failureCloseDone());
        assertArrayEquals(new byte[]{1, 2, 3, 77, 78}, wire.array());
        assertEquals(0, application.position());
        assertThrows(SSLException.class, () -> driver.writePlaintext(application));
    }

    private static TlsEngineDriver driver(Engine engine) throws SSLException {
        return new TlsEngineDriver(engine, task -> fail("Unexpected delegated task"), () -> { });
    }

    @FunctionalInterface
    interface Step { SSLEngineResult run(ByteBuffer source, ByteBuffer target) throws SSLException; }

    static final class Engine extends SSLEngine {
        final Deque<Step> wraps = new ArrayDeque<>();
        final Deque<Step> unwraps = new ArrayDeque<>();
        final Deque<Runnable> delegates = new ArrayDeque<>();
        final AtomicBoolean taskRunning = new AtomicBoolean();
        SSLEngineResult.HandshakeStatus handshake;
        int packetSize = 4;
        int applicationSize = 4;
        int calls;
        boolean inboundDone;
        boolean outboundDone;

        // Keep the provider fixture limited to session properties actually consumed by this driver.
        final SSLSession session = (SSLSession) Proxy.newProxyInstance(SSLSession.class.getClassLoader(),
            new Class<?>[]{SSLSession.class}, (proxy, method, arguments) -> {
                switch (method.getName()) {
                    case "getPacketBufferSize": return packetSize;
                    case "getApplicationBufferSize": return applicationSize;
                    default: throw new AssertionError("Unexpected session method: " + method.getName());
                }
            });

        Engine(SSLEngineResult.HandshakeStatus handshake) { this.handshake = handshake; }

        SSLEngineResult result(SSLEngineResult.Status status, SSLEngineResult.HandshakeStatus next, int consumed, int produced) {
            handshake = next == FINISHED ? NOT_HANDSHAKING : next;
            return new SSLEngineResult(status, next, consumed, produced);
        }

        private void called() { assertFalse(taskRunning.get(), "Engine accessed during delegated task"); calls++; }
        @Override public SSLEngineResult wrap(ByteBuffer[] sources, int offset, int length, ByteBuffer target) throws SSLException {
            called(); assertEquals(1, length); return wraps.remove().run(sources[offset], target);
        }
        @Override public SSLEngineResult unwrap(ByteBuffer source, ByteBuffer[] targets, int offset, int length) throws SSLException {
            called(); assertEquals(1, length); return unwraps.remove().run(source, targets[offset]);
        }
        @Override public Runnable getDelegatedTask() { called(); return delegates.poll(); }
        @Override public void closeInbound() throws SSLException { called(); throw new SSLException("Missing close-notify"); }
        @Override public boolean isInboundDone() { called(); return inboundDone; }
        @Override public void closeOutbound() { called(); handshake = NEED_WRAP; }
        @Override public boolean isOutboundDone() { called(); return outboundDone; }
        @Override public SSLSession getSession() { called(); return session; }
        @Override public void beginHandshake() { called(); }
        @Override public SSLEngineResult.HandshakeStatus getHandshakeStatus() { called(); return handshake; }
        @Override public String getApplicationProtocol() { called(); return "h2"; }
        @Override public String[] getSupportedCipherSuites() { throw new UnsupportedOperationException(); }
        @Override public String[] getEnabledCipherSuites() { throw new UnsupportedOperationException(); }
        @Override public void setEnabledCipherSuites(String[] suites) { throw new UnsupportedOperationException(); }
        @Override public String[] getSupportedProtocols() { throw new UnsupportedOperationException(); }
        @Override public String[] getEnabledProtocols() { throw new UnsupportedOperationException(); }
        @Override public void setEnabledProtocols(String[] protocols) { throw new UnsupportedOperationException(); }
        @Override public void setUseClientMode(boolean mode) { throw new UnsupportedOperationException(); }
        @Override public boolean getUseClientMode() { throw new UnsupportedOperationException(); }
        @Override public void setNeedClientAuth(boolean need) { throw new UnsupportedOperationException(); }
        @Override public boolean getNeedClientAuth() { throw new UnsupportedOperationException(); }
        @Override public void setWantClientAuth(boolean want) { throw new UnsupportedOperationException(); }
        @Override public boolean getWantClientAuth() { throw new UnsupportedOperationException(); }
        @Override public void setEnableSessionCreation(boolean flag) { throw new UnsupportedOperationException(); }
        @Override public boolean getEnableSessionCreation() { throw new UnsupportedOperationException(); }
    }
}
