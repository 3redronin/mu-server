package io.muserver;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;

/** Nonblocking server-wide admission, independent of executor or connection capacity. */
final class RequestAdmission {
    private final int maximum;
    private final AtomicInteger admitted = new AtomicInteger();

    RequestAdmission(int maximum) { this.maximum = maximum; }

    @Nullable Slot tryAcquire() {
        for (;;) {
            int current = admitted.get();
            if (maximum != 0 && current >= maximum) return null;
            if (admitted.compareAndSet(current, current + 1)) return new Slot();
        }
    }

    final class Slot implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();
        @Override public void close() {
            if (released.compareAndSet(false, true)) admitted.decrementAndGet();
        }
    }
}
