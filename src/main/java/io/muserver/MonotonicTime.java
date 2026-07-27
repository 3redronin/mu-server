package io.muserver;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic elapsed-time arithmetic. Values returned by {@link System#nanoTime()}
 * are opaque points on a wrapping timeline, so they must only be compared by
 * subtraction.
 */
final class MonotonicTime {

    private MonotonicTime() {
    }

    static long deadlineAfterMillis(long durationMillis) {
        return deadlineAfter(
            System.nanoTime(),
            TimeUnit.MILLISECONDS.toNanos(Math.max(0L, durationMillis))
        );
    }

    static long nanosUntil(long deadlineNanos) {
        return nanosUntil(deadlineNanos, System.nanoTime());
    }

    static long elapsedNanosSince(long startNanos) {
        return System.nanoTime() - startNanos;
    }

    static long elapsedMillis(long startNanos, long endNanos) {
        long elapsedNanos = endNanos - startNanos;
        return elapsedNanos <= 0L
            ? 0L
            : TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
    }

    static long elapsedMillisSince(long startNanos) {
        return elapsedMillis(startNanos, System.nanoTime());
    }

    static boolean isAfter(long candidateNanos, long referenceNanos) {
        return candidateNanos - referenceNanos > 0L;
    }

    static void publishLatest(AtomicLong destination, long candidateNanos) {
        long current = destination.get();
        while (isAfter(candidateNanos, current)
            && !destination.compareAndSet(current, candidateNanos)) {
            current = destination.get();
        }
    }

    static long deadlineAfter(long nowNanos, long durationNanos) {
        return nowNanos + Math.max(0L, durationNanos);
    }

    static long nanosUntil(long deadlineNanos, long nowNanos) {
        return deadlineNanos - nowNanos;
    }
}
