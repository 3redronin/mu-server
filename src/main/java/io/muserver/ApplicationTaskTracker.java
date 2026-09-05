package io.muserver;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Tracks accepted callbacks until they run or Mu cancels them, without Phaser's party limit. */
final class ApplicationTaskTracker {
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition drained = lock.newCondition();
    // Guarded by lock.
    private long pending;

    Registration register() {
        lock.lock();
        try {
            pending++;
            return new Registration();
        } finally {
            lock.unlock();
        }
    }

    boolean awaitUntil(long deadlineNanos) {
        lock.lock();
        try {
            while (pending != 0) {
                long remaining = MonotonicTime.nanosUntil(deadlineNanos);
                if (remaining <= 0) {
                    return false;
                }
                try {
                    drained.awaitNanos(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    final class Registration implements AutoCloseable {
        private final AtomicBoolean completed = new AtomicBoolean();

        @Override
        public void close() {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            lock.lock();
            try {
                if (--pending == 0) {
                    drained.signalAll();
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
