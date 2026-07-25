package io.muserver.rest;

import io.muserver.ClientDisconnectedException;
import io.muserver.MuException;
import io.muserver.Mutils;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

class SseBroadcasterImpl implements SseBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SseBroadcasterImpl.class);
    private static final Runnable NO_OP = () -> { };

    private final List<BiConsumer<SseEventSink, Throwable>> errorListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<SseEventSink>> closeListeners = new CopyOnWriteArrayList<>();
    private final Set<SinkRegistration> sinks = new HashSet<>();
    private final ReentrantLock stateLock = new ReentrantLock();
    private CloseMode closeMode = CloseMode.OPEN;

    @Override
    public void onError(BiConsumer<SseEventSink, Throwable> onError) {
        Mutils.notNull("onError", onError);
        stateLock.lock();
        try {
            throwIfClosed();
            this.errorListeners.add(onError);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void onClose(Consumer<SseEventSink> onClose) {
        Mutils.notNull("onClose", onClose);
        stateLock.lock();
        try {
            throwIfClosed();
            this.closeListeners.add(onClose);
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public void register(SseEventSink sseEventSink) {
        Mutils.notNull("sseEventSink", sseEventSink);
        stateLock.lock();
        try {
            throwIfClosed();
            SinkRegistration registration = new SinkRegistration(sseEventSink);
            this.sinks.add(registration);
            if (sseEventSink instanceof JaxSseEventSinkImpl) {
                try {
                    registration.responseCompleteHandlerRemoval =
                        ((JaxSseEventSinkImpl) sseEventSink).addResponseCompleteHandler(info -> {
                            if (!info.completedSuccessfully()) {
                    Exception ex;
                    switch (info.response().responseState()) {
                        case CLIENT_CANCELLED:
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
                                onSinkErrored(registration, ex, FailureSource.RESPONSE_COMPLETION);
                            }
                        });
                } catch (RuntimeException | Error e) {
                    sinks.remove(registration);
                    throw e;
                }
            }
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    public CompletionStage<?> broadcast(OutboundSseEvent event) {
        Mutils.notNull("event", event);
        List<SinkRegistration> currentSinks;
        stateLock.lock();
        try {
            throwIfClosed();
            currentSinks = List.copyOf(sinks);
        } finally {
            stateLock.unlock();
        }

        CompletableFuture<?> completableFuture = new CompletableFuture<>();

        AtomicInteger count = new AtomicInteger(currentSinks.size());
        if (currentSinks.isEmpty()) {
            completableFuture.complete(null);
            return completableFuture;
        }
        for (SinkRegistration registration : currentSinks) {
            try {
                SseEventSink sink = registration.sink;
                if (sink.isClosed()) {
                    onSinkClosed(registration);
                    sendComplete(completableFuture, count);
                } else {
                    sink.send(event).whenComplete((o, throwable) -> {
                        try {
                            if (throwable != null) {
                                onSinkErrored(registration, throwable, FailureSource.BROADCAST);
                            }
                        } finally {
                            sendComplete(completableFuture, count);
                        }
                    });
                }
            } catch (IllegalStateException e) {
                try {
                    onSinkClosed(registration);
                } finally {
                    sendComplete(completableFuture, count);
                }
            } catch (Throwable e) {
                try {
                    onSinkErrored(registration, e, FailureSource.BROADCAST);
                } finally {
                    sendComplete(completableFuture, count);
                }
            }
        }

        return completableFuture;
    }

    private void onSinkErrored(SinkRegistration registration, Throwable throwable, FailureSource failureSource) {
        boolean notify;
        stateLock.lock();
        try {
            boolean wasRegistered = sinks.remove(registration);
            notify = (wasRegistered
                || (failureSource == FailureSource.BROADCAST && closeMode != CloseMode.CLOSED_NON_CASCADING))
                && !registration.errorNotified;
            if (notify) {
                registration.errorNotified = true;
            }
        } finally {
            stateLock.unlock();
        }
        removeResponseCompleteHandler(registration);
        if (!notify) {
            return;
        }
        Throwable closeFailure = null;
        try {
            registration.sink.close();
        } catch (Throwable e) {
            closeFailure = e;
        }
        sendOnErrorEvent(registration.sink, throwable);
        if (closeFailure != null) {
            sendOnErrorEvent(registration.sink, closeFailure);
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
        List<SinkRegistration> currentSinks;
        List<SinkRegistration> sinksToClose;
        stateLock.lock();
        try {
            if (closeMode != CloseMode.OPEN) {
                return;
            }
            closeMode = cascading ? CloseMode.CLOSED_CASCADING : CloseMode.CLOSED_NON_CASCADING;
            currentSinks = List.copyOf(sinks);
            sinksToClose = cascading ? currentSinks : List.of();
            sinks.clear();
        } finally {
            stateLock.unlock();
        }
        for (SinkRegistration registration : currentSinks) {
            removeResponseCompleteHandler(registration);
        }
        for (SinkRegistration registration : sinksToClose) {
            try {
                registration.sink.close();
                onSinkClosed(registration);
            } catch (Throwable e) {
                sendOnErrorEvent(registration.sink, e);
            }
        }
    }

    private void onSinkClosed(SinkRegistration registration) {
        boolean notify;
        stateLock.lock();
        try {
            sinks.remove(registration);
            notify = !registration.closeNotified;
            registration.closeNotified = true;
        } finally {
            stateLock.unlock();
        }
        removeResponseCompleteHandler(registration);
        if (notify) {
            for (Consumer<SseEventSink> closeListener : closeListeners) {
                try {
                    closeListener.accept(registration.sink);
                } catch (Throwable e) {
                    log.warn("Unhandled exception from SSE close listener", e);
                }
            }
        }
    }

    private static void removeResponseCompleteHandler(SinkRegistration registration) {
        try {
            registration.responseCompleteHandlerRemoval.run();
        } catch (Throwable e) {
            log.warn("Unable to remove SSE response completion listener", e);
        }
    }

    private void sendOnErrorEvent(SseEventSink sink, Throwable throwable) {
        for (BiConsumer<SseEventSink, Throwable> errorListener : errorListeners) {
            try {
                errorListener.accept(sink, throwable);
            } catch (Throwable e) {
                log.warn("Unhandled exception from SSE error listener", e);
            }
        }
    }

    private void throwIfClosed() {
        if (closeMode != CloseMode.OPEN) {
            throw new IllegalStateException("This broadcaster has already been closed");
        }
    }

    public int connectedSinksCount() {
        stateLock.lock();
        try {
            return sinks.size();
        } finally {
            stateLock.unlock();
        }
    }

    private enum CloseMode {
        OPEN,
        CLOSED_CASCADING,
        CLOSED_NON_CASCADING
    }

    private enum FailureSource {
        BROADCAST,
        RESPONSE_COMPLETION
    }

    private static class SinkRegistration {
        private final SseEventSink sink;
        private boolean closeNotified;
        private boolean errorNotified;
        private Runnable responseCompleteHandlerRemoval = NO_OP;

        private SinkRegistration(SseEventSink sink) {
            this.sink = sink;
        }
    }
}
