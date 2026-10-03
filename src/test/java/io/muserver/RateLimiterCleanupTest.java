package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.RateLimitBuilder.rateLimit;
import static org.junit.jupiter.api.Assertions.*;

class RateLimiterCleanupTest {
    @ParameterizedTest
    @ValueSource(longs = {1L, Long.MAX_VALUE - 5L})
    void idleBucketsExpireWithoutAnotherRequestOrDiagnosticQuery(long start) throws Exception {
        var now = new AtomicLong(start);
        var name = new AtomicReference<>("expired");
        var limiter = new RateLimiterImpl(request -> rateLimit().withBucket(name.get())
            .withRate(1).withWindow(name.get().equals("expired") ? 10 : 100, TimeUnit.NANOSECONDS).build(), now::get);
        try (var fixture = new CleanupServer(List.of(limiter))) {
            limiter.record(null);
            name.set("live");
            limiter.record(null);
            now.addAndGet(10);
            fixture.timer.tick();
            assertEquals(2, storedBuckets(limiter).size(), "Timer must dispatch, not do cleanup inline");
            fixture.internal.runNext();
            assertEquals(Set.of("live"), storedBuckets(limiter).keySet());
            assertNotNull(limiter.record(null), "Cleanup must preserve the live bucket's allowance");
            now.addAndGet(90);
            fixture.timer.tick();
            fixture.internal.runNext();
            assertTrue(storedBuckets(limiter).isEmpty());
        }
    }

    @Test
    void maintenanceIsCoalescedForAllLimitersAndQueuedCleanupStopsWithTheServer() throws Exception {
        var now = new AtomicLong(1L);
        var first = shortLivedLimiter(now);
        var second = shortLivedLimiter(now);
        try (var fixture = new CleanupServer(List.of(first, second))) {
            first.record(null);
            second.record(null);
            now.addAndGet(10);
            for (int i = 0; i < 5; i++) fixture.timer.tick();
            assertEquals(1, fixture.internal.tasks.size());
            fixture.internal.runNext();
            assertTrue(storedBuckets(first).isEmpty());
            assertTrue(storedBuckets(second).isEmpty());

            first.record(null);
            now.addAndGet(10);
            fixture.timer.tick();
            assertEquals(1, fixture.internal.tasks.size(), "A finished sweep must allow the next one");
            fixture.server.stop(0, TimeUnit.SECONDS);
            assertTrue(fixture.timer.isShutdown());
            assertTrue(fixture.timer.future.isCancelled());
            fixture.internal.runNext();
            assertEquals(1, storedBuckets(first).size(), "A sweep queued before shutdown must stop");
        }
    }

    @Test
    void aRejectedMaintenanceSubmissionCanBeRetried() throws Exception {
        var now = new AtomicLong(1L);
        var limiter = shortLivedLimiter(now);
        try (var fixture = new CleanupServer(List.of(limiter))) {
            limiter.record(null);
            now.addAndGet(10);
            fixture.internal.rejectNext = true;
            fixture.timer.tick();
            assertTrue(fixture.internal.tasks.isEmpty());
            fixture.timer.tick();
            assertEquals(1, fixture.internal.tasks.size());
            fixture.internal.runNext();
            assertTrue(storedBuckets(limiter).isEmpty());
        }
    }

    @Test
    void noMaintenanceIsScheduledWithoutRateLimiters() {
        try (var fixture = new CleanupServer(List.of())) {
            assertNull(fixture.timer.periodic);
            assertTrue(fixture.internal.tasks.isEmpty());
        }
    }

    @Test
    void aRequestReplacingAnExpiredBucketDuringTheSweepKeepsItsNewAllowance() throws Exception {
        var now = new AtomicLong(1L);
        var limiter = shortLivedLimiter(now);
        limiter.record(null);
        now.addAndGet(10);
        var visits = new AtomicInteger();
        limiter.removeExpiredBuckets(() -> {
            // The maintenance iterator already has the key; a request replaces its queue before locking.
            if (visits.getAndIncrement() == 0) assertNull(limiter.record(null));
            return false;
        });
        assertEquals(1, storedBuckets(limiter).get("bucket").size());
        assertNotNull(limiter.record(null));
    }

    @Test
    void aLargeExpiredBucketReleasesTheDecisionLockAndCanStopBetweenBatches() throws Exception {
        var now = new AtomicLong(1L);
        var name = new AtomicReference<>("large");
        var limiter = new RateLimiterImpl(request -> rateLimit().withBucket(name.get())
            .withRate(3000).withWindow(10, TimeUnit.NANOSECONDS).build(), now::get);
        for (int i = 0; i < 2050; i++) limiter.record(null);
        now.addAndGet(10);
        var decisions = Executors.newSingleThreadExecutor();
        var batches = new AtomicInteger();
        try {
            limiter.removeExpiredBuckets(() -> {
                if (batches.getAndIncrement() == 0) return false;
                name.set("live");
                try {
                    assertNull(decisions.submit(() -> limiter.record(null)).get(5, TimeUnit.SECONDS));
                } catch (Exception failure) {
                    throw new AssertionError("Another request must make progress between cleanup batches", failure);
                }
                return true;
            });
            int remaining = storedBuckets(limiter).get("large").size();
            assertTrue(remaining > 0 && remaining < 2050, "Cleanup must yield before draining a large bucket");
            assertEquals(1, storedBuckets(limiter).get("live").size());
            limiter.removeExpiredBuckets(() -> false);
            assertEquals(Set.of("live"), storedBuckets(limiter).keySet());
        } finally { decisions.shutdownNow(); }
    }

    private static RateLimiterImpl shortLivedLimiter(AtomicLong now) {
        return new RateLimiterImpl(request -> rateLimit().withBucket("bucket")
            .withRate(1).withWindow(10, TimeUnit.NANOSECONDS).build(), now::get);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Queue<Long>> storedBuckets(RateLimiterImpl limiter) throws Exception {
        Field field = RateLimiterImpl.class.getDeclaredField("map");
        field.setAccessible(true);
        return (Map<String, Queue<Long>>) field.get(limiter);
    }

    private static final class CleanupServer implements AutoCloseable {
        final QueuedExecutor internal = new QueuedExecutor();
        final ManualTimer timer = new ManualTimer();
        final MuServer server;

        CleanupServer(List<RateLimiterImpl> limiters) {
            var builder = MuServerBuilder.httpServer().withIdleTimeout(0, TimeUnit.MILLISECONDS);
            builder.rateLimiters = limiters;
            builder.executionResourcesFactory = (supplied, mode) -> new ExecutionResources(internal, false, internal, timer);
            server = builder.start();
        }

        public void close() {
            server.stop(0, TimeUnit.SECONDS);
            while (!internal.tasks.isEmpty()) internal.runNext();
            timer.shutdownNow();
        }
    }

    private static final class QueuedExecutor extends AbstractExecutorService {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean shutdown;
        boolean rejectNext;
        public void execute(Runnable task) {
            if (shutdown || rejectNext) {
                rejectNext = false;
                throw new RejectedExecutionException();
            }
            tasks.add(task);
        }
        void runNext() { tasks.remove().run(); }
        public void shutdown() { shutdown = true; }
        public List<Runnable> shutdownNow() { shutdown = true; var remaining = List.copyOf(tasks); tasks.clear(); return remaining; }
        public boolean isShutdown() { return shutdown; }
        public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }

    private static final class ManualTimer extends ScheduledThreadPoolExecutor {
        Runnable periodic;
        ScheduledFuture<?> future;
        ManualTimer() { super(1); setRemoveOnCancelPolicy(true); }
        @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long initial, long period, TimeUnit unit) {
            assertNull(periodic, "Only one rate-limit maintenance timer per server");
            assertEquals(1, unit.toSeconds(initial));
            assertEquals(1, unit.toSeconds(period));
            periodic = task;
            // Keep a real cancellable scheduler registration, but advance it manually in tests.
            return future = super.scheduleAtFixedRate(task, 1, 1, TimeUnit.DAYS);
        }
        void tick() { assertNotNull(periodic, "Rate-limit expiry must be scheduled"); periodic.run(); }
    }
}
