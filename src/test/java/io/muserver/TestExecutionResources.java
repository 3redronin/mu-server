package io.muserver;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import org.jspecify.annotations.Nullable;

/** Test-only controls for deterministic scheduling and resource lifecycle assertions. */
public final class TestExecutionResources {
    private TestExecutionResources() { }

    public static MuServerBuilder configure(MuServerBuilder builder,
        @Nullable ExecutorService connections, @Nullable ExecutorService writers,
        @Nullable ExecutorService internal, @Nullable ScheduledExecutorService timer) {
        builder.executionResourcesFactory = supplied -> new ExecutionResources(
            supplied == null ? MuServerBuilder.defaultExecutor() : supplied, supplied == null,
            internal == null ? MuServerBuilder.defaultExecutor() : internal,
            timer == null ? MuServerBuilder.defaultTimerExecutor() : timer) {
            @Override ExecutorService connectionExecutor() {
                return connections == null ? super.connectionExecutor() : connections;
            }
            @Override ExecutorService writerExecutor() {
                return writers == null ? super.writerExecutor() : writers;
            }
            @Override void shutdown() {
                super.shutdown();
                if (connections != null) connections.shutdown();
                if (writers != null) writers.shutdown();
            }
        };
        return builder;
    }
}
