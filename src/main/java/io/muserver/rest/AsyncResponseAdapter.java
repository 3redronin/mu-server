package io.muserver.rest;

import io.muserver.*;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.CompletionCallback;
import jakarta.ws.rs.container.ConnectionCallback;
import jakarta.ws.rs.container.TimeoutHandler;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

class AsyncResponseAdapter implements AsyncResponse, ResponseCompleteListener {
    private static final Logger log = LoggerFactory.getLogger(AsyncResponseAdapter.class);

    private final AsyncHandle asyncHandle;
    private final Consumer resultConsumer;
    private final Object stateLock = new Object();
    private State state = State.SUSPENDED;
    private boolean cancelled;
    private long timeoutGeneration;
    private @Nullable Future<?> timeoutTask;
    private @Nullable TimeoutHandler timeoutHandler;
    private final List<ConnectionCallback> connectionCallbacks = new ArrayList<>();
    private final List<CompletionCallback> completionCallbacks = new ArrayList<>();
    private volatile @Nullable Throwable exceptionWhileWriting;

    AsyncResponseAdapter(AsyncHandle asyncHandle, Consumer resultConsumer) {
        this.asyncHandle = asyncHandle;
        this.resultConsumer = resultConsumer;
        asyncHandle.addResponseCompleteHandler(this);
    }

    @Override
    public boolean resume(@Nullable Object response) {
        return beginResuming(response, false);
    }

    @Override
    public boolean resume(Throwable response) {
        return beginResuming(Objects.requireNonNull(response, "response"), false);
    }

    @Override
    public boolean cancel() {
        return doCancel(null);
    }

    @Override
    public boolean cancel(int retryAfter) {
        return doCancel(retryAfter);
    }

    @Override
    public boolean cancel(Date retryAfter) {
        return doCancel(Mutils.toHttpDate(Objects.requireNonNull(retryAfter, "retryAfter")));
    }

    private boolean doCancel(@Nullable Object retryAfterValue) {
        Response.ResponseBuilder response = Response.status(503);
        if (retryAfterValue != null) {
            response.header(HeaderNames.RETRY_AFTER.toString(), retryAfterValue);
        }
        return beginResuming(response.build(), true);
    }

    private boolean beginResuming(@Nullable Object response, boolean cancellation) {
        @Nullable Future<?> timeoutToCancel;
        synchronized (stateLock) {
            if (cancellation && cancelled) {
                return true;
            }
            if (state != State.SUSPENDED) {
                return false;
            }
            state = State.RESUMING;
            cancelled = cancellation;
            timeoutToCancel = clearTimeoutLocked();
        }
        cancel(timeoutToCancel);
        try {
            asyncHandle.executeApplicationTask(() -> processResponse(response));
        } catch (Throwable dispatchFailure) {
            exceptionWhileWriting = dispatchFailure;
            markDone();
            asyncHandle.complete(dispatchFailure);
        }
        return true;
    }

    private void processResponse(@Nullable Object response) {
        try {
            if (response instanceof Throwable && !(response instanceof Exception)) {
                Throwable failure = (Throwable) response;
                exceptionWhileWriting = failure;
                asyncHandle.complete(failure);
            } else {
                resultConsumer.accept(response);
                asyncHandle.complete();
            }
        } catch (Throwable responseFailure) {
            exceptionWhileWriting = responseFailure;
            try {
                asyncHandle.complete(responseFailure);
            } catch (Throwable completionFailure) {
                addSuppressedIfDifferent(responseFailure, completionFailure);
            }
        } finally {
            markDone();
        }
    }

    @Override
    public boolean isSuspended() {
        synchronized (stateLock) {
            return state == State.SUSPENDED;
        }
    }

    @Override
    public boolean isCancelled() {
        synchronized (stateLock) {
            return cancelled;
        }
    }

    @Override
    public boolean isDone() {
        synchronized (stateLock) {
            return state == State.DONE;
        }
    }

    @Override
    public boolean setTimeout(long time, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        @Nullable Future<?> previousTimeout;
        long generation;
        synchronized (stateLock) {
            if (state != State.SUSPENDED) {
                return false;
            }
            generation = ++timeoutGeneration;
            previousTimeout = timeoutTask;
            timeoutTask = null;
        }
        cancel(previousTimeout);

        if (time <= 0) {
            return true;
        }

        Future<?> scheduled = asyncHandle.scheduleApplicationTask(
            () -> timeoutExpired(generation),
            time,
            unit
        );
        boolean keepScheduled;
        synchronized (stateLock) {
            keepScheduled = state == State.SUSPENDED && timeoutGeneration == generation;
            if (keepScheduled) {
                timeoutTask = scheduled;
            }
        }
        if (!keepScheduled) {
            scheduled.cancel(false);
        }
        return true;
    }

    private void timeoutExpired(long generation) {
        @Nullable TimeoutHandler handler;
        synchronized (stateLock) {
            if (state != State.SUSPENDED || timeoutGeneration != generation) {
                return;
            }
            timeoutTask = null;
            handler = timeoutHandler;
        }

        if (handler != null) {
            try {
                handler.handleTimeout(this);
            } catch (Throwable timeoutFailure) {
                resume(timeoutFailure);
                return;
            }
            synchronized (stateLock) {
                if (state != State.SUSPENDED || timeoutGeneration != generation) {
                    return;
                }
            }
        }
        resume(defaultTimeoutException());
    }

    private static ServiceUnavailableException defaultTimeoutException() {
        return new ServiceUnavailableException(Response.status(503)
            .type(MediaType.TEXT_HTML_TYPE)
            .entity("<h1>503 Service Unavailable</h1><p>Timed out</p>")
            .build());
    }

    @Override
    public void setTimeoutHandler(TimeoutHandler handler) {
        synchronized (stateLock) {
            timeoutHandler = Objects.requireNonNull(handler, "handler");
        }
    }

    @Override
    public Collection<Class<?>> register(Class<?> callback) {
        Objects.requireNonNull(callback, "callback");
        throw new NotImplementedException("Mu-Server does not instantiate classes for you. Please use register(Object) with an instantiated callback instead.");
    }

    @Override
    public Map<Class<?>, Collection<Class<?>>> register(Class<?> callback, Class<?>... callbacks) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(callbacks, "callbacks");
        for (Class<?> additionalCallback : callbacks) {
            Objects.requireNonNull(additionalCallback, "callbacks element");
        }
        throw new NotImplementedException("Mu-Server does not instantiate classes for you. Please use register(Object, Object...) with instantiated callbacks instead.");
    }

    @Override
    public Collection<Class<?>> register(Object callback) {
        Objects.requireNonNull(callback, "callback");
        Collection<Class<?>> added = new HashSet<>();
        synchronized (stateLock) {
            if (callback instanceof ConnectionCallback) {
                added.add(ConnectionCallback.class);
                connectionCallbacks.add((ConnectionCallback) callback);
            }
            if (callback instanceof CompletionCallback) {
                added.add(CompletionCallback.class);
                completionCallbacks.add((CompletionCallback) callback);
            }
        }
        return added;
    }

    @Override
    public Map<Class<?>, Collection<Class<?>>> register(Object callback, Object... callbacks) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(callbacks, "callbacks");
        Map<Class<?>, Collection<Class<?>>> added = new HashMap<>();
        register(callback, added);
        for (Object additionalCallback : callbacks) {
            register(Objects.requireNonNull(additionalCallback, "callbacks element"), added);
        }
        return added;
    }

    private void register(Object callback, Map<Class<?>, Collection<Class<?>>> added) {
        Collection<Class<?>> registered = register(callback);
        Class<?> callbackClass = callback.getClass();
        added.computeIfAbsent(callbackClass, ignored -> new HashSet<>()).addAll(registered);
    }

    @Override
    public void onComplete(ResponseInfo info) {
        List<ConnectionCallback> connectionCallbackSnapshot;
        List<CompletionCallback> completionCallbackSnapshot;
        @Nullable Future<?> timeoutToCancel;
        synchronized (stateLock) {
            state = State.DONE;
            timeoutToCancel = clearTimeoutLocked();
            connectionCallbackSnapshot = new ArrayList<>(connectionCallbacks);
            completionCallbackSnapshot = new ArrayList<>(completionCallbacks);
        }
        cancel(timeoutToCancel);

        if (!info.completedSuccessfully() && info.response().responseState() == ResponseState.CLIENT_DISCONNECTED) {
            for (ConnectionCallback connectionCallback : connectionCallbackSnapshot) {
                try {
                    connectionCallback.onDisconnect(this);
                } catch (Exception e) {
                    log.warn("Exception from calling onDisconnect on " + connectionCallback, e);
                }
            }
        }
        for (CompletionCallback completionCallback : completionCallbackSnapshot) {
            try {
                completionCallback.onComplete(exceptionWhileWriting);
            } catch (Exception e) {
                log.warn("Exception from calling onComplete on " + completionCallback, e);
            }
        }
    }

    private void markDone() {
        @Nullable Future<?> timeoutToCancel;
        synchronized (stateLock) {
            state = State.DONE;
            timeoutToCancel = clearTimeoutLocked();
        }
        cancel(timeoutToCancel);
    }

    private @Nullable Future<?> clearTimeoutLocked() {
        timeoutGeneration++;
        Future<?> task = timeoutTask;
        timeoutTask = null;
        return task;
    }

    private static void cancel(@Nullable Future<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    @SuppressWarnings("ReferenceEquality") // Throwable forbids suppressing itself; identity is required.
    private static void addSuppressedIfDifferent(Throwable failure, Throwable suppressed) {
        if (failure != suppressed) {
            failure.addSuppressed(suppressed);
        }
    }

    interface Consumer {
        void accept(@Nullable Object response) throws Exception;
    }

    private enum State {
        SUSPENDED,
        RESUMING,
        DONE
    }
}
