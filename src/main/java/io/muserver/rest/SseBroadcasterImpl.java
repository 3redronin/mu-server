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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

class SseBroadcasterImpl implements SseBroadcaster {

    private volatile boolean isClosed = false;
    private final List<BiConsumer<SseEventSink, Throwable>> errorListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SseEventSink>> closeListeners = new CopyOnWriteArrayList<>();
    private final List<SinkRegistration> sinks = new CopyOnWriteArrayList<>();

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
        SinkRegistration registration = new SinkRegistration(sseEventSink);
        this.sinks.add(registration);
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
                    onSinkErrored(registration, ex);
                }
            });
        }
    }

    @Override
    public CompletionStage<?> broadcast(OutboundSseEvent event) {
        Mutils.notNull("event", event);
        List<SinkRegistration> currentSinks;
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
        for (SinkRegistration registration : currentSinks) {
            SseEventSink sink = registration.sink;
            if (sink.isClosed()) {
                sinks.remove(registration);
                sendOnCloseEvent(registration);
                sendComplete(completableFuture, count);
            } else {
                try {
                    sink.send(event).whenComplete((o, throwable) -> {
                        if (throwable != null) {
                            onSinkErrored(registration, throwable);
                        }
                        sendComplete(completableFuture, count);
                    });
                } catch (IllegalStateException e) {
                    sinks.remove(registration);
                    sendOnCloseEvent(registration);
                    sendComplete(completableFuture, count);
                }
            }
        }

        return completableFuture;
    }

    private void onSinkErrored(SinkRegistration registration, Throwable throwable) {
        boolean wasInList = sinks.remove(registration);
        if (wasInList) {
            try {
                registration.sink.close();
            } catch (Exception ignored) {
            }
            for (BiConsumer<SseEventSink, Throwable> errorListener : errorListeners) {
                errorListener.accept(registration.sink, throwable);
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
    public void close(boolean cascading) {
        List<SinkRegistration> sinksToClose;
        synchronized (this) {
            if (isClosed) {
                return;
            }
            isClosed = true;
            sinksToClose = cascading ? List.copyOf(sinks) : List.of();
            sinks.clear();
        }
        for (SinkRegistration registration : sinksToClose) {
            try {
                registration.sink.close();
                sendOnCloseEvent(registration);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private void sendOnCloseEvent(SinkRegistration registration) {
        if (registration.closeNotified.compareAndSet(false, true)) {
            for (Consumer<SseEventSink> closeListener : closeListeners) {
                closeListener.accept(registration.sink);
            }
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

    private static class SinkRegistration {
        private final SseEventSink sink;
        private final AtomicBoolean closeNotified = new AtomicBoolean();

        private SinkRegistration(SseEventSink sink) {
            this.sink = sink;
        }
    }
}
