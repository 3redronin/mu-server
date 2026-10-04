package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ConnectionAdmissionTest {
    @Test void configurationAndUnlimitedDefault() {
        for (MuServerBuilder builder : List.of(MuServerBuilder.httpServer(), MuServerBuilder.httpsServer(), MuServerBuilder.muServer())) {
            assertEquals(50, builder.listenBacklog());
            assertSame(builder, builder.withListenBacklog(7));
            assertEquals(7, builder.listenBacklog());
            assertEquals(0, builder.maxConnections());
            builder.withMaxConnections(12);
            assertEquals(7, builder.listenBacklog(), "Backlog and connection admission are independent");
            assertThrows(IllegalArgumentException.class, () -> builder.withListenBacklog(0));
            assertThrows(IllegalArgumentException.class, () -> builder.withListenBacklog(-1));
            assertEquals(7, builder.listenBacklog());
        }
        assertEquals(0, MuServerBuilder.httpServer().maxConnections());
        assertEquals(12, MuServerBuilder.httpServer().withMaxConnections(12).maxConnections());
        assertThrows(IllegalArgumentException.class, () -> MuServerBuilder.httpServer().withMaxConnections(-1));
        ConnectionAdmission unlimited = new ConnectionAdmission(0);
        for (int i = 0; i < 20; i++) {
            assertTrue(unlimited.awaitCapacity(() -> true));
            assertTrue(unlimited.tryAdmit(new Socket()));
        }
        assertEquals(0, unlimited.admittedCount(), "Unlimited admission does not retain sockets");
        unlimited.stop();
        assertFalse(unlimited.awaitCapacity(() -> true));
        assertFalse(unlimited.tryAdmit(new Socket()));
    }

    @Test void waitingDoesNotReserveCapacityAndLateReleaseIsIdempotent() {
        ConnectionAdmission limit = new ConnectionAdmission(1);
        assertTrue(limit.awaitCapacity(() -> true));
        assertTrue(limit.awaitCapacity(() -> true), "An idle listener must not reserve the slot");
        assertEquals(0, limit.admittedCount());
        Socket first = new Socket(), second = new Socket();
        assertTrue(limit.tryAdmit(first));
        assertFalse(limit.tryAdmit(second));
        limit.release(first);
        assertTrue(limit.tryAdmit(second));
        limit.release(first); // e.g. a timed-out setup worker retires after its replacement was admitted
        assertEquals(1, limit.admittedCount());
        assertFalse(limit.tryAdmit(new Socket()));
        limit.release(second);
        assertEquals(0, limit.admittedCount());
    }

    @Test void simultaneousListenersCannotBothAdmitTheLastSlot() throws Exception {
        ConnectionAdmission limit = new ConnectionAdmission(1);
        var workers = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(8), start = new CountDownLatch(1);
        List<Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) attempts.add(workers.submit(() -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return limit.tryAdmit(new Socket());
            }));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int admitted = 0;
            for (Future<Boolean> attempt : attempts) if (attempt.get(5, TimeUnit.SECONDS)) admitted++;
            assertEquals(1, admitted);
            assertEquals(1, limit.admittedCount());
        } finally { start.countDown(); workers.shutdownNow(); }
    }

    @Test void releaseWakesAFullListener() throws Exception {
        ConnectionAdmission limit = new ConnectionAdmission(1);
        Socket first = new Socket();
        assertTrue(limit.tryAdmit(first));
        var worker = Executors.newSingleThreadExecutor();
        CountDownLatch waiting = new CountDownLatch(1);
        try {
            var result = worker.submit(() -> limit.awaitCapacity(() -> { waiting.countDown(); return true; }));
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            limit.release(first);
            assertTrue(result.get(5, TimeUnit.SECONDS));
        } finally { limit.stop(); worker.shutdownNow(); }
    }

    @Test void listenerStopWakesOnlyThatListenerAndServerStopRejectsNewAdmission() throws Exception {
        ConnectionAdmission limit = new ConnectionAdmission(1);
        assertTrue(limit.tryAdmit(new Socket()));
        var worker = Executors.newSingleThreadExecutor();
        AtomicBoolean accepting = new AtomicBoolean(true);
        CountDownLatch waiting = new CountDownLatch(1), waitingAgain = new CountDownLatch(1);
        try {
            var result = worker.submit(() -> limit.awaitCapacity(() -> { waiting.countDown(); return accepting.get(); }));
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            accepting.set(false);
            limit.signalWaiters();
            assertFalse(result.get(5, TimeUnit.SECONDS));
            assertFalse(limit.isStopped());
            var next = worker.submit(() -> limit.awaitCapacity(() -> { waitingAgain.countDown(); return true; }));
            assertTrue(waitingAgain.await(5, TimeUnit.SECONDS));
            limit.stop();
            assertFalse(next.get(5, TimeUnit.SECONDS));
            assertFalse(limit.tryAdmit(new Socket()));
        } finally { limit.stop(); worker.shutdownNow(); }
    }

    @Test void interruptionStopsWaitingAndPreservesInterruptStatus() throws Exception {
        ConnectionAdmission limit = new ConnectionAdmission(1);
        assertTrue(limit.tryAdmit(new Socket()));
        CountDownLatch waiting = new CountDownLatch(1);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            boolean admitted = limit.awaitCapacity(() -> { waiting.countDown(); return true; });
            result.complete(!admitted && Thread.currentThread().isInterrupted());
        });
        try {
            worker.start();
            assertTrue(waiting.await(5, TimeUnit.SECONDS));
            worker.interrupt();
            assertTrue(result.get(5, TimeUnit.SECONDS));
        } finally { limit.stop(); worker.interrupt(); worker.join(5000); }
    }
}
