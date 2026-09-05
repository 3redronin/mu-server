package io.muserver;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One application's callback mailbox; never holds an I/O worker while callbacks run. */
final class SerialApplicationTasks {
    private final Mu3ServerImpl server;
    private final Queue<Entry> tasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean();

    SerialApplicationTasks(Mu3ServerImpl server) { this.server = server; }

    void submit(Runnable task, Consumer<RejectedExecutionException> onRejected) {
        tasks.add(new Entry(task, onRejected));
        if (running.compareAndSet(false, true)) {
            RejectedExecutionException rejected = server.executeTrackedApplicationTask(this::drain, "async callback");
            if (rejected != null) {
                Entry entry;
                while ((entry = tasks.poll()) != null) entry.onRejected.accept(rejected);
                running.set(false);
                // A concurrent submit may have observed running before it was cleared.
                if (!tasks.isEmpty()) scheduleRemaining();
            }
        }
    }

    private void scheduleRemaining() {
        if (!running.compareAndSet(false, true)) return;
        RejectedExecutionException rejected = server.executeTrackedApplicationTask(this::drain, "async callback");
        if (rejected != null) {
            Entry entry;
            while ((entry = tasks.poll()) != null) entry.onRejected.accept(rejected);
            running.set(false);
            if (!tasks.isEmpty()) scheduleRemaining();
        }
    }

    private void drain() {
        for (;;) {
            Entry entry;
            while ((entry = tasks.poll()) != null) entry.task.run();
            running.set(false);
            if (tasks.isEmpty() || !running.compareAndSet(false, true)) return;
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
