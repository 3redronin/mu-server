package io.muserver;

import org.jspecify.annotations.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Serializes accepted output while keeping I/O outcomes independent of application workers. */
final class AsyncResponseOutput {
    @FunctionalInterface
    interface Writer { void write(ByteBuffer data) throws Exception; }
    @FunctionalInterface
    interface AsyncWriter { CompletableFuture<@Nullable Void> write(ByteBuffer data) throws Exception; }

    private final Executor executor;
    private final AsyncWriter writer;
    private final Consumer<Boolean> abort;
    private final SerialApplicationTasks callbacks;
    private final ReentrantLock lock = new ReentrantLock();
    // Guarded by lock.
    private final Queue<PendingWrite> pending = new ArrayDeque<>();
    private final CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
    // Guarded by lock.
    private boolean completionRequested;
    // Guarded by lock.
    private @Nullable Throwable failure;
    // Guarded by lock.
    private @Nullable PendingWrite active;
    // Guarded by lock. Only its completed notification may resume the suspended drain.
    private @Nullable CompletableFuture<@Nullable Void> activeIo;
    // Guarded by lock.
    private boolean draining;
    /** Prevents an empty I/O drain from publishing failure before transport abort has returned. */
    // Guarded by lock.
    private boolean abortFinished;
    // Removed queued writes still need their futures settled before exchange retirement.
    private int pendingSettlements;
    private int pendingCallbackDispatches;
    private @Nullable Throwable fatalAbortFailure;
    private final java.util.concurrent.atomic.AtomicBoolean abortRequested = new java.util.concurrent.atomic.AtomicBoolean();

    AsyncResponseOutput(Executor executor, Writer writer, Consumer<Boolean> abort, SerialApplicationTasks callbacks) {
        this(data -> {
            writer.write(data);
            return CompletableFuture.completedFuture(null);
        }, executor, abort, callbacks);
    }

    static AsyncResponseOutput asynchronous(Executor executor, AsyncWriter writer, Consumer<Boolean> abort,
                                            SerialApplicationTasks callbacks) {
        return new AsyncResponseOutput(writer, executor, abort, callbacks);
    }

    private AsyncResponseOutput(AsyncWriter writer, Executor executor, Consumer<Boolean> abort, SerialApplicationTasks callbacks) {
        this.executor = executor;
        this.writer = writer;
        this.abort = abort;
        this.callbacks = callbacks;
    }

    CompletableFuture<@Nullable Void> completion() { return completion; }

    boolean completionIsPending() {
        lock.lock();
        try { return !completionRequested; }
        finally { lock.unlock(); }
    }

    Future<@Nullable Void> write(ByteBuffer data, @Nullable DoneCallback callback) {
        java.util.Objects.requireNonNull(data, "data");
        PendingWrite write = new PendingWrite(data, callback);
        boolean schedule = false;
        boolean rejected;
        lock.lock();
        try {
            rejected = completionRequested;
            if (!rejected) {
                pending.add(write);
                if (!draining) { draining = true; schedule = true; }
            }
        } finally { lock.unlock(); }
        if (rejected) {
            write.finish(new IllegalStateException("The asynchronous response is already complete"));
        } else if (schedule) scheduleDrain();
        return write.result;
    }

    void complete(@Nullable Throwable cause) {
        lock.lock();
        try {
            if (completionRequested) return;
            completionRequested = true;
            if (cause != null) failure = cause;
        } finally { lock.unlock(); }
        if (cause != null) fail(cause);
        else finishIfDrained();
    }

    private void fail(Throwable cause) {
        if (completion.isDone()) return;
        Queue<PendingWrite> cancelled = new ArrayDeque<>();
        boolean activeOutput;
        Throwable terminalFailure;
        lock.lock();
        try {
            completionRequested = true;
            if (failure == null) failure = cause;
            terminalFailure = failure;
            activeOutput = active != null;
            if (!activeOutput) {
                cancelled.addAll(pending);
                pending.clear();
                pendingSettlements += cancelled.size();
                draining = false;
            }
        } finally { lock.unlock(); }
        // The transport abort does not return buffers to callers. The I/O drain must
        // acknowledge termination before active futures and callbacks can finish.
        if (abortRequested.compareAndSet(false, true)) {
            try { abort.accept(activeOutput); }
            catch (Throwable failedAbort) {
                suppress(terminalFailure, failedAbort);
                if (failedAbort instanceof VirtualMachineError || failedAbort instanceof ThreadDeath) {
                    lock.lock();
                    try { fatalAbortFailure = failedAbort; } finally { lock.unlock(); }
                }
            }
            finally {
                lock.lock();
                try { abortFinished = true; } finally { lock.unlock(); }
            }
        }
        for (PendingWrite write : cancelled) {
            try { write.finish(terminalFailure, true); }
            finally {
                lock.lock();
                try { pendingSettlements--; } finally { lock.unlock(); }
            }
        }
        finishIfDrained();
    }

    @SuppressWarnings("ReferenceEquality") // A Throwable cannot suppress itself.
    private static void suppress(Throwable failure, Throwable secondary) {
        if (failure != secondary) failure.addSuppressed(secondary);
    }

    private void drain() {
        for (int step = 0; step < 64; step++) {
            PendingWrite write;
            Throwable writeFailure;
            CompletableFuture<@Nullable Void> io;
            boolean start;
            lock.lock();
            try {
                write = active;
                start = write == null;
                if (start) write = pending.poll();
                if (write == null) {
                    draining = false;
                    io = null;
                } else {
                    active = write;
                    io = activeIo;
                }
                writeFailure = failure;
            } finally { lock.unlock(); }
            if (write == null) { finishIfDrained(); return; }
            if (start) {
                io = CompletableFuture.completedFuture(null);
                if (writeFailure == null) {
                    try { io = java.util.Objects.requireNonNull(writer.write(write.data)); }
                    catch (Throwable ioFailure) { fail(ioFailure); }
                }
                lock.lock();
                try { activeIo = io; } finally { lock.unlock(); }
            }
            CompletableFuture<@Nullable Void> writing = java.util.Objects.requireNonNull(io);
            if (!writing.isDone()) {
                // Do not touch active state after registering: an immediate completion may
                // already have dispatched the next bounded turn on another worker.
                writing.whenComplete((ignored, error) -> scheduleDrain());
                return;
            }
            try { writing.join(); }
            catch (Throwable ioFailure) {
                Throwable cause = ioFailure;
                while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
                fail(cause);
            }
            lock.lock();
            try {
                if (failure != null) writeFailure = failure;
            } finally { lock.unlock(); }
            write.finish(writeFailure, true);
            lock.lock();
            try { active = null; activeIo = null; } finally { lock.unlock(); }
            if (writeFailure instanceof VirtualMachineError || writeFailure instanceof ThreadDeath) {
                fail(writeFailure);
                FatalErrors.rethrow(writeFailure);
            }
        }
        scheduleDrain();
    }

    private void scheduleDrain() {
        try { executor.execute(this::drain); }
        catch (RejectedExecutionException rejected) {
            // A transport future may notify us on a selector. Terminal callbacks and exchange
            // cleanup must stay off that notifier, including with caller-runs application code.
            Thread cleanup = new Thread(() -> {
                fail(rejected);
                drain();
            }, "mu-response-rejected-writer");
            cleanup.setDaemon(true);
            cleanup.start();
        }
    }

    private void finishIfDrained() {
        boolean finish;
        Throwable terminalFailure;
        Throwable fatalAbort;
        lock.lock();
        try {
            finish = completionRequested && active == null && pending.isEmpty() && pendingSettlements == 0 && pendingCallbackDispatches == 0
                && (failure == null || abortFinished);
            terminalFailure = failure;
            fatalAbort = finish ? fatalAbortFailure : null;
            if (finish) fatalAbortFailure = null;
        } finally { lock.unlock(); }
        if (finish) {
            if (terminalFailure == null) completion.complete(null);
            else completion.completeExceptionally(terminalFailure);
            // Rethrow only after the underlying write and every queued future released ownership.
            if (fatalAbort != null) FatalErrors.rethrow(fatalAbort);
        }
    }

    private void notifyCallback(@Nullable DoneCallback callback, @Nullable Throwable error, boolean defer) {
        if (callback == null) return;
        Runnable notification = () -> {
            try { callback.onComplete(error); }
            catch (Throwable callbackFailure) { fail(callbackFailure); FatalErrors.rethrow(callbackFailure); }
        };
        if (defer) {
            lock.lock();
            try { pendingCallbackDispatches++; } finally { lock.unlock(); }
            callbacks.submitLater(executor, notification, this::fail, () -> {
                lock.lock();
                try { pendingCallbackDispatches--; } finally { lock.unlock(); }
                finishIfDrained();
            });
        } else callbacks.submit(notification, this::fail);
    }

    private final class PendingWrite {
        private final ByteBuffer data;
        private final @Nullable DoneCallback callback;
        private final WriteFuture result = new WriteFuture(AsyncResponseOutput.this);

        PendingWrite(ByteBuffer data, @Nullable DoneCallback callback) {
            this.data = data;
            this.callback = callback;
        }

        void finish(@Nullable Throwable error) {
            finish(error, false);
        }

        void finish(@Nullable Throwable error, boolean defer) {
            result.finish(error);
            // Dispatch from the response, so delayed callbacks do not capture this payload entry.
            notifyCallback(callback, error, defer);
        }
    }

    /** A completed public future must not keep its response, queued writes or payload alive. */
    private static final class WriteFuture implements Future<@Nullable Void> {
        private final CompletableFuture<@Nullable Void> result = new CompletableFuture<>();
        private volatile @Nullable AsyncResponseOutput owner;

        WriteFuture(AsyncResponseOutput owner) { this.owner = owner; }

        void finish(@Nullable Throwable error) {
            owner = null;
            if (error == null) result.complete(null);
            else result.completeExceptionally(error);
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            AsyncResponseOutput output = owner;
            if (output == null || result.isDone()) return false;
            output.fail(new CancellationException("Asynchronous output was cancelled"));
            boolean interrupted = false;
            for (;;) {
                try { result.get(); break; }
                catch (InterruptedException e) { interrupted = true; }
                catch (ExecutionException | CancellationException e) { break; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            return result.isCancelled();
        }
        @Override public boolean isCancelled() { return result.isCancelled(); }
        @Override public boolean isDone() { return result.isDone(); }
        @Override public @Nullable Void get() throws InterruptedException, ExecutionException { return result.get(); }
        @Override public @Nullable Void get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            return result.get(timeout, unit);
        }
    }
}
