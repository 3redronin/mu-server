package io.muserver;

import java.util.ArrayDeque;
import java.util.Queue;
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

    SerialApplicationTasks(Mu3ServerImpl server) { this.server = server; }

    void submit(Runnable task, Consumer<RejectedExecutionException> onRejected) {
        synchronized (lock) {
            tasks.add(new Entry(task, onRejected));
            if (scheduled) return;
            scheduled = true;
        }
        RejectedExecutionException rejected = server.executeTrackedApplicationTask(this::drain, "async callback");
        if (rejected != null) {
            Queue<Entry> rejectedTasks;
            synchronized (lock) {
                rejectedTasks = new ArrayDeque<>(tasks);
                tasks.clear();
                scheduled = false;
            }
            for (Entry entry : rejectedTasks) entry.onRejected.accept(rejected);
        }
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
