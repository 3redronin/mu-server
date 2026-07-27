package io.muserver;

import java.time.Instant;

/**
 * The socket-accept event represented in the two clock domains that consume it.
 * The epoch timestamp is exposed to callers, while only the monotonic point is
 * used for elapsed-time calculations.
 */
final class ConnectionAcceptedTime {
    private final Instant instant;
    private final long monotonicNanos;

    private ConnectionAcceptedTime(Instant instant, long monotonicNanos) {
        this.instant = instant;
        this.monotonicNanos = monotonicNanos;
    }

    static ConnectionAcceptedTime now() {
        long monotonicNanos = System.nanoTime();
        Instant instant = Instant.now();
        return new ConnectionAcceptedTime(instant, monotonicNanos);
    }

    static ConnectionAcceptedTime of(Instant instant, long monotonicNanos) {
        return new ConnectionAcceptedTime(instant, monotonicNanos);
    }

    Instant instant() {
        return instant;
    }

    long elapsedMillisUntil(long endNanos) {
        return MonotonicTime.elapsedMillis(monotonicNanos, endNanos);
    }
}
