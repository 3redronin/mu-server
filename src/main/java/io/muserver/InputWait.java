package io.muserver;

import org.jspecify.annotations.Nullable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** One cancellable race between input readiness and its deadline; callbacks only schedule work. */
final class InputWait {
    @FunctionalInterface interface Timer { ScheduledFuture<?> schedule(Runnable task, long timeoutMillis); }
    @FunctionalInterface interface Ready { void resume(@Nullable Throwable failure, boolean timedOut); }

    private final CompletableFuture<Void> readiness;
    private final AtomicBoolean claimed = new AtomicBoolean();
    private volatile @Nullable ScheduledFuture<?> timer;

    InputWait(CompletableFuture<Void> readiness) { this.readiness = readiness; }

    /** The owner must publish this wait before starting it: ready input can resume immediately. */
    void start(long timeoutMillis, Timer scheduler, Ready ready) {
        readiness.whenComplete((ignored, failure) -> resume(ready, failure, false));
        if (timeoutMillis > 0 && !claimed.get()) {
            try {
                ScheduledFuture<?> scheduled = scheduler.schedule(() -> resume(ready, null, true), timeoutMillis);
                timer = scheduled;
                // Cancellation or readiness may win before timer publication.
                if (claimed.get()) scheduled.cancel(false);
            } catch (RejectedExecutionException rejected) { resume(ready, rejected, false); }
        }
    }

    private void resume(Ready ready, @Nullable Throwable failure, boolean timedOut) {
        if (!claimed.compareAndSet(false, true)) return;
        cancel();
        ready.resume(failure, timedOut);
    }

    void cancel() {
        claimed.set(true);
        readiness.cancel(false);
        ScheduledFuture<?> scheduled = timer;
        if (scheduled != null) scheduled.cancel(false);
    }
}
