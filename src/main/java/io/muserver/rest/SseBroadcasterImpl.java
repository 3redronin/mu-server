package io.muserver.rest;

import io.muserver.Mutils;

import javax.ws.rs.sse.OutboundSseEvent;
import javax.ws.rs.sse.SseBroadcaster;
import javax.ws.rs.sse.SseEventSink;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

class SseBroadcasterImpl implements SseBroadcaster {

    private volatile boolean isClosed = false;
    private final Object lock = new Object();
    private List<BiConsumer<SseEventSink, Throwable>> errorListeners = new ArrayList<>();
    private List<Consumer<SseEventSink>> closeListeners = new ArrayList<>();
    private List<SseEventSink> sinks = new ArrayList<>();

    @Override
    public void onError(BiConsumer<SseEventSink, Throwable> onError) {
        Mutils.notNull("onError", onError);
        synchronized (lock) {
            throwIfClosed();
            this.errorListeners.add(onError);
        }
    }

    @Override
    public void onClose(Consumer<SseEventSink> onClose) {
        Mutils.notNull("onClose", onClose);
        synchronized (lock) {
            throwIfClosed();
            this.closeListeners.add(onClose);
        }
    }

    @Override
    public void register(SseEventSink sseEventSink) {
        Mutils.notNull("sseEventSink", sseEventSink);
        synchronized (lock) {
            throwIfClosed();
            this.sinks.add(sseEventSink);
        }
    }

    @Override
    public CompletionStage<?> broadcast(OutboundSseEvent event) {
        Mutils.notNull("event", event);
        synchronized (lock) {
            throwIfClosed();

            CompletableFuture<?> completableFuture = new CompletableFuture<>();

            AtomicInteger count = new AtomicInteger(sinks.size());
            for (SseEventSink sink : sinks) {
                if (sink.isClosed()) {
                    sendOnCloseEvent(sink);
                    sendComplete(completableFuture, count);
                } else {
                    sink.send(event).whenCompleteAsync((o, throwable) -> {
                        if (throwable != null) {
                            synchronized (lock) {
                                sinks.remove(sink);
                            }
                            try {
                                sink.close();
                            } catch (Exception ignored) {
                            }
                            for (BiConsumer<SseEventSink, Throwable> errorListener : errorListeners) {
                                errorListener.accept(sink, throwable);
                            }
                        }
                        sendComplete(completableFuture, count);
                    });
                }
            }

            return completableFuture;
        }
    }

    private static void sendComplete(CompletableFuture<?> completableFuture, AtomicInteger count) {
        int remaining = count.decrementAndGet();
        if (remaining == 0) {
            completableFuture.complete(null);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (!isClosed) {
                for (SseEventSink sink : sinks) {
                    try {
                        sink.close();
                        sendOnCloseEvent(sink);
                    } catch (Exception e) {
                        // ignore
                    }
                }
                isClosed = true;
            }
        }
    }

    private void sendOnCloseEvent(SseEventSink sink) {
        for (Consumer<SseEventSink> closeListener : closeListeners) {
            closeListener.accept(sink);
        }
    }

    private void throwIfClosed() {
        if (isClosed) {
            throw new IllegalStateException("This broadcaster has already been closed");
        }
    }
}
