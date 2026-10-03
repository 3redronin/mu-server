package io.muserver;

import org.jspecify.annotations.Nullable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns the two Mu task domains and dispatch-only timer, including startup rollback. */
class ExecutionResources {
    final ExecutorService application;
    final ExecutorService internal;
    final ScheduledExecutorService timer;
    private final boolean ownsApplication;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();

    @FunctionalInterface
    interface Factory {
        ExecutionResources create(@Nullable ExecutorService application, ThreadingMode mode);
    }

    ExecutionResources(ExecutorService application, boolean ownsApplication,
                       ExecutorService internal, ScheduledExecutorService timer) {
        this.application = application;
        this.ownsApplication = ownsApplication;
        this.internal = internal;
        this.timer = timer;
    }

    static ExecutionResources create(@Nullable ExecutorService supplied, ThreadingMode mode) {
        ExecutorService application = supplied == null ? MuServerBuilder.defaultExecutor(mode) : supplied;
        ExecutorService internal = null;
        try {
            internal = MuServerBuilder.defaultExecutor(mode);
            return new ExecutionResources(application, supplied == null, internal,
                MuServerBuilder.defaultTimerExecutor());
        } catch (RuntimeException | Error failure) {
            if (internal != null) internal.shutdown();
            if (supplied == null) application.shutdown();
            throw failure;
        }
    }

    ExecutorService connectionExecutor() { return internal; }

    ExecutorService writerExecutor() { return internal; }

    void shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) return;
        timer.shutdown();
        internal.shutdown();
        if (!ownsApplication) return;
        if (internal.isTerminated()) {
            application.shutdown();
        } else {
            // Closing a socket releases I/O asynchronously. Keep the application executor
            // alive until those workers have submitted their final write callbacks.
            // Do not extend stop(timeout), or run application callbacks on I/O workers.
            Thread shutdown = new Thread(() -> {
                boolean interrupted = false;
                for (;;) {
                    try {
                        if (internal.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)) break;
                    } catch (InterruptedException e) { interrupted = true; }
                }
                application.shutdown();
                if (interrupted) Thread.currentThread().interrupt();
            }, "mu-executor-shutdown");
            shutdown.setDaemon(true);
            shutdown.start();
        }
    }
}
