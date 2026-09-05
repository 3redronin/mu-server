package io.muserver;

import org.jspecify.annotations.Nullable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/** Owns the two Mu task domains and dispatch-only timer, including startup rollback. */
class ExecutionResources {
    final ExecutorService application;
    final ExecutorService internal;
    final ScheduledExecutorService timer;
    private final boolean ownsApplication;

    @FunctionalInterface
    interface Factory {
        ExecutionResources create(@Nullable ExecutorService application);
    }

    ExecutionResources(ExecutorService application, boolean ownsApplication,
                       ExecutorService internal, ScheduledExecutorService timer) {
        this.application = application;
        this.ownsApplication = ownsApplication;
        this.internal = internal;
        this.timer = timer;
    }

    static ExecutionResources create(@Nullable ExecutorService supplied) {
        ExecutorService application = supplied == null ? MuServerBuilder.defaultExecutor() : supplied;
        ExecutorService internal = null;
        try {
            internal = MuServerBuilder.defaultExecutor();
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
        timer.shutdown();
        internal.shutdown();
        if (ownsApplication) application.shutdown();
    }
}
