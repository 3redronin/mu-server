package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Assigns each accepted connection permanently to one owner; TLS task capacity is shared. */
final class ChannelTransportGroup {
    private final List<ChannelTransportLoop> loops;
    private int next; // Acceptor only.
    private volatile boolean stopping;

    ChannelTransportGroup(ConnectionAcceptor acceptor, Mu3ServerImpl server,
                          @Nullable HAProxyProtocolConfig proxyConfig, int count) throws IOException {
        if (count < 1) throw new IllegalArgumentException("Positive transport thread count required");
        int workers = Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors()));
        AtomicInteger sequence = new AtomicInteger();
        var tasks = new ThreadPoolExecutor(workers, workers, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(256), task -> {
                Thread worker = new Thread(task, "mu-tls-task-" + acceptor.address().getPort() + "-" + sequence.incrementAndGet());
                worker.setDaemon(true);
                return worker;
            });
        var created = new ArrayList<ChannelTransportLoop>();
        try {
            for (int i = 0; i < count; i++) {
                var loop = new ChannelTransportLoop(acceptor, server, proxyConfig, tasks, i);
                try { created.add(loop); }
                catch (RuntimeException | Error failure) { loop.closeIfUnstarted(); throw failure; }
            }
            loops = List.copyOf(created);
        } catch (IOException | RuntimeException | Error failure) {
            for (ChannelTransportLoop loop : created) loop.closeIfUnstarted();
            tasks.shutdownNow();
            throw failure;
        }
    }

    void start() {
        try {
            for (ChannelTransportLoop loop : loops) loop.start();
        } catch (RuntimeException | Error failure) {
            stopAfterConnections();
            throw failure;
        }
    }

    void accept(Socket socket, ConnectionAcceptedTime acceptedTime, boolean http2Enabled) throws IOException {
        if (stopping) throw new RejectedExecutionException("Channel listener is stopping");
        ChannelTransportLoop loop = loops.get(next);
        next = (next + 1) % loops.size();
        loop.accept(socket, acceptedTime, http2Enabled);
    }

    void stopAfterConnections() {
        stopping = true;
        for (ChannelTransportLoop loop : loops) loop.stopAfterConnections();
    }

    void closeIfUnstarted() {
        for (ChannelTransportLoop loop : loops) loop.closeIfUnstarted();
    }
}
