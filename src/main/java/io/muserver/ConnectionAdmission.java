package io.muserver;

import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Shared socket admission; idle listeners never reserve another listener's capacity. */
final class ConnectionAdmission {
    private final int maximum;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition capacity = lock.newCondition();
    // Guarded by lock. Identity of each accepted socket makes retirement idempotent.
    private final Set<Socket> admitted = new HashSet<>();
    private volatile boolean stopped;

    ConnectionAdmission(int maximum) { this.maximum = maximum; }
    boolean isLimited() { return maximum != 0; }
    boolean isStopped() { return stopped; }

    boolean awaitCapacity(BooleanSupplier accepting) {
        if (!isLimited()) return !stopped && accepting.getAsBoolean();
        lock.lock();
        try {
            while (!stopped && accepting.getAsBoolean() && admitted.size() >= maximum) {
                try { capacity.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
            }
            return !stopped && accepting.getAsBoolean();
        } finally { lock.unlock(); }
    }

    boolean tryAdmit(Socket socket) {
        if (!isLimited()) return !stopped;
        lock.lock();
        try {
            if (stopped || admitted.size() >= maximum) return false;
            return admitted.add(socket);
        } finally { lock.unlock(); }
    }

    void release(Socket socket) {
        if (!isLimited()) return;
        lock.lock();
        try {
            if (admitted.remove(socket)) capacity.signalAll();
        } finally { lock.unlock(); }
    }

    void signalWaiters() {
        lock.lock();
        try { capacity.signalAll(); }
        finally { lock.unlock(); }
    }

    void stop() {
        lock.lock();
        try { stopped = true; capacity.signalAll(); }
        finally { lock.unlock(); }
    }

    int admittedCount() {
        lock.lock();
        try { return admitted.size(); }
        finally { lock.unlock(); }
    }
}
