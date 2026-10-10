package io.muserver;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns readiness and TLS progression for one listener; application work runs on other executors. */
final class ChannelTransportLoop {
    private static final Logger log = LoggerFactory.getLogger(ChannelTransportLoop.class);
    private static final int TURN_LIMIT = 128;
    final ConnectionAcceptor acceptor;
    final Mu3ServerImpl server;
    final @Nullable HAProxyProtocolConfig proxyConfig;
    final Selector selector;
    final ThreadPoolExecutor tlsTasks;
    private final Thread thread;
    private final Set<ChannelConnection> connections = new HashSet<>(); // Owner only.
    private final ConcurrentLinkedQueue<ChannelConnection> ready = new ConcurrentLinkedQueue<>();
    private volatile boolean stopping;
    private volatile boolean exited;
    private final Object submissionLock = new Object();

    ChannelTransportLoop(ConnectionAcceptor acceptor, Mu3ServerImpl server,
                         @Nullable HAProxyProtocolConfig proxyConfig) throws IOException {
        this.acceptor = acceptor;
        this.server = server;
        this.proxyConfig = proxyConfig;
        selector = Selector.open();
        int workers = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        AtomicInteger sequence = new AtomicInteger();
        ThreadPoolExecutor taskExecutor = null;
        try {
            taskExecutor = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256), task -> {
                    Thread worker = new Thread(task, "mu-tls-task-" + acceptor.address().getPort() + "-" + sequence.incrementAndGet());
                    worker.setDaemon(true);
                    return worker;
                });
            tlsTasks = taskExecutor;
            thread = new Thread(this::run, "mu-channel-loop-" + acceptor.address().getPort());
            // The acceptor owns server liveness. Application tasks may outlive a forced stop;
            // this daemon can observe their eventual retirement without extending stop(timeout).
            thread.setDaemon(true);
        } catch (RuntimeException | Error failure) {
            if (taskExecutor != null) taskExecutor.shutdownNow();
            try { selector.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    void start() { thread.start(); }

    void accept(Socket socket, ConnectionAcceptedTime acceptedTime, boolean http2Enabled) throws IOException {
        if (stopping) throw new RejectedExecutionException("Channel listener is stopping");
        ChannelConnection connection = new ChannelConnection(this, socket, acceptedTime, http2Enabled);
        if (!connection.schedule()) {
            connection.loopFailed();
            throw new RejectedExecutionException("Channel listener has exited");
        }
    }

    boolean enqueue(ChannelConnection connection) {
        // Serialize publication with exit, so an accepted socket cannot fall between the final
        // queue drain and selector closure. Continuations after exit are harmless.
        synchronized (submissionLock) {
            if (exited) return false;
            ready.add(connection);
        }
        selector.wakeup();
        return true;
    }

    void retired(ChannelConnection connection) {
        if (!exited) connections.remove(connection);
        acceptor.channelRetired(connection.socket, connection.connection());
    }

    void stopAfterConnections() {
        stopping = true;
        tlsTasks.shutdownNow();
        selector.wakeup();
        closeIfUnstarted();
    }

    void closeIfUnstarted() {
        if (thread.getState() == Thread.State.NEW) {
            stopping = true;
            tlsTasks.shutdownNow();
            try { selector.close(); } catch (IOException ignored) { }
        }
    }

    private void run() {
        long nextScan = System.nanoTime();
        @Nullable ChannelConnection[] batch = new @Nullable ChannelConnection[TURN_LIMIT];
        try {
            while (!stopping || !connections.isEmpty() || !ready.isEmpty()) {
                int count = 0;
                for (; count < TURN_LIMIT; count++) {
                    ChannelConnection connection = ready.poll();
                    if (connection == null) break;
                    batch[count] = connection;
                    if (!connection.isRetired()) connections.add(connection);
                }
                // A connection that schedules itself during this batch waits for the next
                // selection turn, so one busy connection cannot postpone polling new readiness.
                for (int i = 0; i < count; i++) {
                    ChannelConnection connection = java.util.Objects.requireNonNull(batch[i]);
                    batch[i] = null;
                    connection.scheduled.set(false);
                    if (!connection.isRetired()) connection.drive();
                }
                if (ready.isEmpty() && selector.selectedKeys().isEmpty()) selector.select(50);
                else selector.selectNow();
                var selected = selector.selectedKeys().iterator();
                for (int i = 0; i < TURN_LIMIT && selected.hasNext(); i++) {
                    SelectionKey key = selected.next();
                    selected.remove();
                    if (key.isValid()) ((ChannelConnection) key.attachment()).schedule();
                }
                long now = System.nanoTime();
                if (now - nextScan >= 0) {
                    // Deadline checks only schedule work, so this iteration cannot remove entries.
                    for (ChannelConnection connection : connections) connection.checkDeadline(now, stopping);
                    nextScan = now + TimeUnit.MILLISECONDS.toNanos(50);
                }
            }
        } catch (Throwable failure) {
            log.error("Channel transport loop failed", failure);
            // Reject further accepts and cancel the listener timers without waiting on this owner.
            acceptor.stopUntil(System.nanoTime());
            FatalErrors.rethrow(failure);
        } finally {
            synchronized (submissionLock) { stopping = true; exited = true; }
            for (ChannelConnection connection : connections.toArray(new ChannelConnection[0])) connection.loopFailed();
            ChannelConnection pending;
            while ((pending = ready.poll()) != null) pending.loopFailed();
            connections.clear();
            tlsTasks.shutdownNow();
            try { selector.close(); } catch (IOException ignored) { }
        }
    }
}
