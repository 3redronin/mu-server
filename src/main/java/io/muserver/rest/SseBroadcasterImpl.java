package io.muserver.rest;

import io.muserver.ClientDisconnectedException;
import io.muserver.MuException;
import io.muserver.Mutils;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

class SseBroadcasterImpl implements SseBroadcaster {

    private volatile boolean isClosed = false;
    private final List<BiConsumer<SseEventSink, Throwable>> errorListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SseEventSink>> closeListeners = new CopyOnWriteArrayList<>();
    private final List<SseEventSink> sinks = new CopyOnWriteArrayList<>();

    @Override
    public synchronized void onError(BiConsumer<SseEventSink, Throwable> onError) {
        Mutils.notNull("onError", onError);
        throwIfClosed();
        this.errorListeners.add(onError);
    }

    @Override
    public synchronized void onClose(Consumer<SseEventSink> onClose) {
        Mutils.notNull("onClose", onClose);
        throwIfClosed();
        this.closeListeners.add(onClose);
    }

    @Override
    public synchronized void register(SseEventSink sseEventSink) {
        Mutils.notNull("sseEventSink", sseEventSink);
        throwIfClosed();
        this.sinks.add(sseEventSink);
        if (sseEventSink instanceof JaxSseEventSinkImpl) {
            ((JaxSseEventSinkImpl) sseEventSink).setResponseCompleteHandler(info -> {
                if (!info.completedSuccessfully()) {
                    Exception ex;
                    switch (info.response().responseState()) {
                        case CLIENT_DISCONNECTED:
                            ex = new ClientDisconnectedException();
                            break;
                        case TIMED_OUT:
                            ex = new TimeoutException();
                            break;
                        default:
                        case ERRORED:
                            ex = new MuException("Generic error");
                    }
                    onSinkErrored(sseEventSink, ex);
                }
            });
        }
    }

    @Override
    public CompletionStage<?> broadcast(OutboundSseEvent event) {
        Mutils.notNull("event", event);
        List<SseEventSink> currentSinks;
        synchronized (this) {
            throwIfClosed();
            currentSinks = List.copyOf(sinks);
        }

        CompletableFuture<?> completableFuture = new CompletableFuture<>();

        AtomicInteger count = new AtomicInteger(currentSinks.size());
        if (currentSinks.isEmpty()) {
            completableFuture.complete(null);
            return completableFuture;
        }
        for (SseEventSink sink : currentSinks) {
            if (sink.isClosed()) {
                sinks.remove(sink);
                sendOnCloseEvent(sink);
                sendComplete(completableFuture, count);
            } else {
                try {
                    sink.send(event).whenComplete((o, throwable) -> {
                        if (throwable != null) {
                            onSinkErrored(sink, throwable);
                        }
                        sendComplete(completableFuture, count);
                    });
                } catch (IllegalStateException e) {
                    if (sinks.remove(sink)) {
                        sendOnCloseEvent(sink);
                    }
                    sendComplete(completableFuture, count);
                }
            }
        }

        return completableFuture;
    }

    private void onSinkErrored(SseEventSink sink, Throwable throwable) {
        boolean wasInList = sinks.remove(sink);
        if (wasInList) {
            try {
                sink.close();
            } catch (Exception ignored) {
            }
            for (BiConsumer<SseEventSink, Throwable> errorListener : errorListeners) {
                errorListener.accept(sink, throwable);
            }
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
        close(true);
    }

    @Override
    public synchronized void close(boolean cascading) {
        if (!isClosed) {
            isClosed = true;
            if (cascading) {
                for (SseEventSink sink : sinks) {
                    try {
                        sink.close();
                        sendOnCloseEvent(sink);
                    } catch (Exception e) {
                        // ignore
                    }
                }
            }
            sinks.clear();
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

    public int connectedSinksCount() {
        return sinks.size();
    }
}
