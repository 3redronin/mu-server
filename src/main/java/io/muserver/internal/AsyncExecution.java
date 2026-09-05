package io.muserver.internal;

import io.muserver.AsyncHandle;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Internal bridge between core exchange dispatch and JAX-RS. Not a supported extension API.
 * @hidden
 */
public interface AsyncExecution {
    void executeApplicationTask(Runnable task);
    Future<?> scheduleApplicationTask(Runnable task, long delay, TimeUnit unit);

    static AsyncExecution forHandle(AsyncHandle handle) {
        if (handle instanceof AsyncExecution) return (AsyncExecution) handle;
        return new AsyncExecution() {
            @Override public void executeApplicationTask(Runnable task) {
                Objects.requireNonNull(task, "task").run();
            }
            @Override public Future<?> scheduleApplicationTask(Runnable task, long delay, TimeUnit unit) {
                Objects.requireNonNull(task, "task");
                return CompletableFuture.runAsync(() -> executeApplicationTask(task),
                    CompletableFuture.delayedExecutor(delay, unit));
            }
        };
    }
}
