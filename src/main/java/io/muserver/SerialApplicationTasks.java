package io.muserver;

import org.jspecify.annotations.Nullable;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** One application's callback mailbox; never holds an I/O worker while callbacks run. */
final class SerialApplicationTasks {
    private final Mu3ServerImpl server;
    private final Object lock = new Object();
    // Guarded by lock.
    private final Queue<Entry> tasks = new ArrayDeque<>();
    // Guarded by lock.
    private boolean scheduled;
    private @Nullable CompletableFuture<Void> dispatchFinished;

    SerialApplicationTasks(Mu3ServerImpl server) { this.server = server; }

    void submit(Runnable task, Consumer<RejectedExecutionException> onRejected) {
        CompletableFuture<Void> dispatch = enqueue(task, onRejected, null);
        if (dispatch != null) dispatch(dispatch);
    }

    /** Reserve callback order now, but keep caller-runs application code outside a serial I/O turn. */
    void submitLater(Executor continuation, Runnable task, Consumer<RejectedExecutionException> onRejected, Runnable dispatched) {
        CompletableFuture<Void> finished = enqueue(task, onRejected, dispatched);
        if (finished == null) return;
        Runnable dispatch = () -> dispatch(finished);
        try { continuation.execute(dispatch); }
        catch (RejectedExecutionException rejected) {
            Thread cleanup = new Thread(dispatch, "mu-rejected-callback-dispatch");
            cleanup.setDaemon(true);
            cleanup.start();
        }
    }

    private @Nullable CompletableFuture<Void> enqueue(Runnable task, Consumer<RejectedExecutionException> onRejected,
                                                       @Nullable Runnable dispatched) {
        boolean start;
        CompletableFuture<Void> finished;
        synchronized (lock) {
            tasks.add(new Entry(task, onRejected));
            start = !scheduled;
            if (start) {
                scheduled = true;
                dispatchFinished = new CompletableFuture<>();
            }
            finished = java.util.Objects.requireNonNull(dispatchFinished);
        }
        // A different producer (including a body callback) may still be submitting the
        // mailbox. All entries share its acceptance/rejection boundary, not just its first entry.
        if (dispatched != null) finished.whenComplete((ignored, failure) -> dispatched.run());
        return start ? finished : null;
    }

    private void dispatch(CompletableFuture<Void> finished) {
        try { dispatch(); }
        finally { finished.complete(null); }
    }

    private void dispatch() {
        RejectedExecutionException rejected;
        try { rejected = server.executeTrackedApplicationTask(this::drain, "async callback"); }
        catch (RuntimeException | Error failure) {
            reject(new RejectedExecutionException("Async callback dispatch failed", failure));
            FatalErrors.rethrow(failure);
            return;
        }
        if (rejected != null) reject(rejected);
    }

    private void reject(RejectedExecutionException rejected) {
        Queue<Entry> rejectedTasks;
        synchronized (lock) {
            rejectedTasks = new ArrayDeque<>(tasks);
            tasks.clear();
            scheduled = false;
        }
        for (Entry entry : rejectedTasks) entry.onRejected.accept(rejected);
    }

    private void drain() {
        for (;;) {
            Entry entry;
            synchronized (lock) {
                entry = tasks.poll();
                if (entry == null) { scheduled = false; return; }
            }
            entry.task.run();
        }
    }

    private static final class Entry {
        final Runnable task;
        final Consumer<RejectedExecutionException> onRejected;
        Entry(Runnable task, Consumer<RejectedExecutionException> onRejected) {
            this.task = task;
            this.onRejected = onRejected;
        }
    }
}
