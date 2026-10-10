package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class InputWaitTest {
    @ParameterizedTest @ValueSource(strings = {"input", "timeout", "cancel", "failure"})
    void exactlyOneOutcomeWinsAndRetiresBothNotifications(String winner) {
        var readiness = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        var outcome = new AtomicReference<Throwable>();
        var timeoutCount = new AtomicInteger();
        var wait = new InputWait(readiness);
        var timer = new Timer();
        wait.start(100, timer, (failure, expired) -> {
            calls.incrementAndGet(); outcome.set(failure);
            if (expired) timeoutCount.incrementAndGet();
        });
        IOException failure = new IOException("Input failed");
        switch (winner) {
            case "input": readiness.complete(null); break;
            case "timeout": timer.task.run(); break;
            case "cancel": wait.cancel(); break;
            case "failure": readiness.completeExceptionally(failure); break;
            default: throw new AssertionError(winner);
        }
        assertTrue(readiness.isDone());
        assertTrue(timer.future.isCancelled());
        readiness.complete(null);
        timer.task.run();
        wait.cancel();
        assertEquals(winner.equals("cancel") ? 0 : 1, calls.get());
        assertEquals(winner.equals("timeout") ? 1 : 0, timeoutCount.get());
        assertSame(winner.equals("failure") ? failure : null, outcome.get());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anOutcomeDuringTimerRegistrationCancelsTheNewlyPublishedTimer(boolean cancel) {
        var readiness = new CompletableFuture<Void>();
        var wait = new InputWait(readiness);
        var calls = new AtomicInteger();
        var timer = new Timer();
        wait.start(10, (task, timeout) -> {
            var future = timer.schedule(task, timeout);
            if (cancel) wait.cancel(); else readiness.complete(null);
            return future;
        }, (failure, expired) -> calls.incrementAndGet());
        assertTrue(timer.future.isCancelled());
        timer.task.run();
        assertEquals(cancel ? 0 : 1, calls.get());
    }

    @Test
    void immediateInputAndPriorCancellationDoNotScheduleTimers() {
        var calls = new AtomicInteger();
        var ready = new InputWait(CompletableFuture.completedFuture(null));
        ready.start(10, (task, timeout) -> { throw new AssertionError("Input already available"); },
            (failure, expired) -> calls.incrementAndGet());
        var cancelled = new InputWait(new CompletableFuture<>());
        cancelled.cancel();
        cancelled.start(10, (task, timeout) -> { throw new AssertionError("Cancelled wait"); },
            (failure, expired) -> calls.incrementAndGet());
        assertEquals(1, calls.get());
    }

    @Test
    void aDisabledDeadlineStillWaitsForInputAndTimerRejectionReleasesReadiness() {
        var readiness = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        new InputWait(readiness).start(0, (task, timeout) -> { throw new AssertionError("Deadline disabled"); },
            (failure, expired) -> calls.incrementAndGet());
        assertFalse(readiness.isDone());
        readiness.complete(null);
        assertEquals(1, calls.get());
        var rejected = new RejectedExecutionException("Timer stopped");
        var second = new CompletableFuture<Void>();
        var error = new AtomicReference<Throwable>();
        new InputWait(second).start(10, (task, timeout) -> { throw rejected; }, (failure, expired) -> error.set(failure));
        assertSame(rejected, error.get());
        assertTrue(second.isCancelled());
    }

    @Test
    void racingInputAndTimeoutResumeAtMostOnce() throws Exception {
        var racers = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 500; i++) {
                var readiness = new CompletableFuture<Void>();
                var calls = new AtomicInteger();
                var timer = new Timer();
                new InputWait(readiness).start(10, timer, (failure, expired) -> calls.incrementAndGet());
                var input = racers.submit(() -> readiness.complete(null));
                var deadline = racers.submit(timer.task);
                input.get(1, TimeUnit.SECONDS);
                deadline.get(1, TimeUnit.SECONDS);
                assertEquals(1, calls.get());
                assertTrue(readiness.isDone());
                assertTrue(timer.future.isCancelled());
            }
        } finally { racers.shutdownNow(); }
    }

    private static final class Timer implements InputWait.Timer {
        Runnable task;
        ScheduledTask future;
        @Override public ScheduledFuture<?> schedule(Runnable task, long timeoutMillis) {
            this.task = task;
            return future = new ScheduledTask(task);
        }
    }

    private static final class ScheduledTask extends FutureTask<Void> implements ScheduledFuture<Void> {
        ScheduledTask(Runnable task) { super(task, null); }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
