package io.muserver;

import com.google.errorprone.annotations.concurrent.GuardedBy;

import io.muserver.internal.FatalErrors;

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

    private final Executor executor;
    private final Writer writer;
    private final Consumer<Boolean> abort;
    private final SerialApplicationTasks callbacks;
    private final ReentrantLock lock = new ReentrantLock();
    @GuardedBy("lock")
    private final Queue<PendingWrite> pending = new ArrayDeque<>();
    private final CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
    @GuardedBy("lock")
    private boolean completionRequested;
    @GuardedBy("lock")
    private @Nullable Throwable failure;
    @GuardedBy("lock")
    private @Nullable PendingWrite active;
    @GuardedBy("lock")
    private boolean draining;
    /** Prevents an empty I/O drain from publishing failure before transport abort has returned. */
    @GuardedBy("lock")
    private boolean abortFinished;
    private final java.util.concurrent.atomic.AtomicBoolean abortRequested = new java.util.concurrent.atomic.AtomicBoolean();

    AsyncResponseOutput(Executor executor, Writer writer, Consumer<Boolean> abort, SerialApplicationTasks callbacks) {
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
        } else if (schedule) {
            try { executor.execute(this::drain); }
            catch (RejectedExecutionException rejectedExecution) { fail(rejectedExecution); }
        }
        return write;
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
                draining = false;
            }
        } finally { lock.unlock(); }
        // The transport abort does not return buffers to callers. The I/O drain must
        // acknowledge termination before active futures and callbacks can finish.
        if (abortRequested.compareAndSet(false, true)) {
            try { abort.accept(activeOutput); }
            finally {
                lock.lock();
                try { abortFinished = true; } finally { lock.unlock(); }
            }
        }
        for (PendingWrite write : cancelled) write.finish(terminalFailure);
        finishIfDrained();
    }

    private void drain() {
        for (;;) {
            PendingWrite write;
            Throwable writeFailure;
            lock.lock();
            try {
                write = pending.poll();
                if (write == null) {
                    draining = false;
                    break;
                }
                active = write;
                writeFailure = failure;
            } finally { lock.unlock(); }
            if (writeFailure == null) {
                try { writer.write(write.data); }
                catch (Throwable ioFailure) {
                    writeFailure = ioFailure;
                    fail(ioFailure);
                }
            }
            lock.lock();
            try {
                if (failure != null) writeFailure = failure;
            } finally { lock.unlock(); }
            write.finish(writeFailure);
            lock.lock();
            try { active = null; } finally { lock.unlock(); }
            if (writeFailure instanceof VirtualMachineError || writeFailure instanceof ThreadDeath) {
                fail(writeFailure);
                FatalErrors.rethrow(writeFailure);
            }
        }
        finishIfDrained();
    }

    private void finishIfDrained() {
        boolean finish;
        Throwable terminalFailure;
        lock.lock();
        try {
            finish = completionRequested && active == null && pending.isEmpty()
                && (failure == null || abortFinished);
            terminalFailure = failure;
        } finally { lock.unlock(); }
        if (finish) {
            if (terminalFailure == null) completion.complete(null);
            else completion.completeExceptionally(terminalFailure);
        }
    }

    private final class PendingWrite implements Future<@Nullable Void> {
        private final ByteBuffer data;
        private final @Nullable DoneCallback callback;
        private final CompletableFuture<@Nullable Void> result = new CompletableFuture<>();

        PendingWrite(ByteBuffer data, @Nullable DoneCallback callback) {
            this.data = data;
            this.callback = callback;
        }

        void finish(@Nullable Throwable error) {
            if (error == null) result.complete(null);
            else result.completeExceptionally(error);
            if (callback != null) callbacks.submit(() -> {
                try { callback.onComplete(error); }
                catch (Throwable callbackFailure) { fail(callbackFailure); FatalErrors.rethrow(callbackFailure); }
            }, AsyncResponseOutput.this::fail);
        }

        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            if (result.isDone()) return false;
            fail(new CancellationException("Asynchronous output was cancelled"));
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
