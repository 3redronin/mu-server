package io.muserver;

import java.util.concurrent.TimeUnit;

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

    static boolean isAfter(long candidateNanos, long referenceNanos) {
        return candidateNanos - referenceNanos > 0L;
    }

    static long deadlineAfter(long nowNanos, long durationNanos) {
        return nowNanos + Math.max(0L, durationNanos);
    }

    static long nanosUntil(long deadlineNanos, long nowNanos) {
        return deadlineNanos - nowNanos;
    }
}
