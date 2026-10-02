package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ServerUtils.httpsServerForTest;

@Timeout(20)
class Http2QueuedWriteBatchTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void queuedTasksWaitForTheSharedTransportOutcome(boolean duringFlush, boolean fail) throws Exception {
        withWriter(writer -> {
            var tasks = new ArrayList<WriteTask>();
            for (int i = 0; i < 3; i++) {
                var task = ping(i);
                tasks.add(task);
                writer.write(task);
            }
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var waiters = Executors.newCachedThreadPool();
            var wire = new ByteArrayOutputStream();
            var counts = new int[2];
            var output = new OutputStream() {
                @Override public void write(int value) { fail("Expected bulk output"); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    counts[0]++;
                    wire.write(bytes, offset, length);
                    if (!duringFlush) pause();
                }
                @Override public void flush() throws IOException {
                    counts[1]++;
                    if (duringFlush) pause();
                }
                private void pause() throws IOException {
                    entered.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out");
                    } catch (InterruptedException e) { throw new IOException(e); }
                    if (fail) throw new IOException("Transport failed");
                }
            };
            try {
                var results = new ArrayList<java.util.concurrent.Future<?>>();
                for (var task : tasks) results.add(waiters.submit(() -> { task.await(); return null; }));
                writer.startWriteLoop(output);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(51, wire.size());
                for (var result : results) assertThrows(TimeoutException.class, () -> result.get(20, TimeUnit.MILLISECONDS));
                assertFalse(writer.testProbe().coordinator().isIdle());
                release.countDown();
                for (var result : results) {
                    if (fail) {
                        var error = assertThrows(java.util.concurrent.ExecutionException.class,
                            () -> result.get(5, TimeUnit.SECONDS));
                        assertEquals("Transport failed", error.getCause().getMessage());
                    } else result.get(5, TimeUnit.SECONDS);
                }
                assertEquals(1, counts[0]);
                assertEquals(duringFlush || !fail ? 1 : 0, counts[1]);
                assertTrue(writer.testProbe().coordinator().isIdle());
                byte[] bytes = wire.toByteArray();
                for (int i = 0; i < 3; i++) assertEquals(i, bytes[i * 17 + 9]);
            } finally {
                release.countDown();
                waiters.shutdownNow();
                assertTrue(waiters.awaitTermination(5, TimeUnit.SECONDS));
            }
        });
    }

    @ParameterizedTest
    @CsvSource({"1,1", "3,1", "70,2"})
    void flushesReadyWorkWithoutWaitingAndBoundsTaskCount(int taskCount, int expectedWrites) throws Exception {
        withWriter(writer -> {
            var tasks = new ArrayList<WriteTask>();
            for (int i = 0; i < taskCount; i++) { var task = ping(i); tasks.add(task); writer.write(task); }
            var counts = new int[2];
            var wire = new ByteArrayOutputStream() {
                @Override public synchronized void write(byte[] bytes, int offset, int length) {
                    counts[0]++;
                    super.write(bytes, offset, length);
                }
                @Override public void flush() { counts[1]++; }
            };
            writer.startWriteLoop(wire);
            for (var task : tasks) task.await(5, TimeUnit.SECONDS);
            assertEquals(expectedWrites, counts[0]);
            assertEquals(expectedWrites, counts[1]);
            assertEquals(17 * taskCount, wire.size());
        });
    }

    @Test
    void flushesHeadersAndOtherStreamsBeforeWaitingForFlowControl() throws Exception {
        withWriter(writer -> {
            var coordinator = writer.testProbe().coordinator();
            coordinator.openStream(1, 0);
            var fields = new FieldBlock(); fields.set(":status", "200");
            var headers = new WriteTask(new Http2HeadersFrame(1, false, fields), true);
            var body = new WriteTask(new Http2DataFrame(1, true, new byte[] {42}, 0, 1), true);
            var control = ping(7);
            writer.write(headers); writer.write(body); writer.write(control);
            var wire = new ByteArrayOutputStream();
            writer.startWriteLoop(wire);
            headers.await(5, TimeUnit.SECONDS);
            control.await(5, TimeUnit.SECONDS);
            int before = wire.size();
            assertTrue(before >= 26);
            assertFalse(coordinator.isIdle());
            coordinator.applyStreamWindowUpdate(1, 1);
            body.await(5, TimeUnit.SECONDS);
            assertEquals(before + 10, wire.size());
        });
    }

    @Test
    void retirementWaitsForEveryFrameAlreadyRemovedFromTheQueue() throws Exception {
        var coordinator = new Http2WriteCoordinator(100);
        coordinator.openStream(1, 100);
        var fields = new FieldBlock(); fields.set(":status", "200");
        coordinator.submit(new WriteTask(new Http2HeadersFrame(1, false, fields), true));
        coordinator.submit(new WriteTask(Http2DataFrame.eos(1), true));
        coordinator.applicationExchangeEnded(1);
        coordinator.processAvailableCommands();
        var first = coordinator.pollWritable(); assertNotNull(first); assertTrue(first.beginWrite());
        var last = coordinator.pollWritable(); assertNotNull(last); assertTrue(last.beginWrite());
        first.complete();
        assertNotNull(coordinator.streamState(1));
        assertFalse(coordinator.isIdle());
        last.complete();
        assertNull(coordinator.streamState(1));
        assertTrue(coordinator.isIdle());
    }

    private static WriteTask ping(int index) {
        return new WriteTask(new Http2Ping(true, new byte[] {(byte) index, 0, 0, 0, 0, 0, 0, 0}), true);
    }

    @FunctionalInterface private interface WriterTest { void run(Http2Connection writer) throws Exception; }

    private static void withWriter(WriterTest test) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = httpsServerForTest("h2").start(); var client = new H2Client(); var con = client.connect(server)) {
            con.handshake();
            var live = (Http2Connection) server.activeConnections().iterator().next();
            var writer = new Http2Connection(live.server, live.creator, live.clientSocket,
                live.clientCertificate, ConnectionAcceptedTime.now(), live.proxyInfo().orElse(null),
                Http2Settings.DEFAULT_CLIENT_SETTINGS, 5000, executor, executor);
            try { test.run(writer); } finally { writer.forceShutdown(); }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
