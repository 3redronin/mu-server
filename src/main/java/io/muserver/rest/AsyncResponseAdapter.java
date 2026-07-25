package io.muserver.rest;

import io.muserver.*;
import io.netty.util.concurrent.DefaultThreadFactory;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.CompletionCallback;
import jakarta.ws.rs.container.ConnectionCallback;
import jakarta.ws.rs.container.TimeoutHandler;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.Nullable;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

class AsyncResponseAdapter implements AsyncResponse, ResponseCompleteListener {
    private static final Logger log = LoggerFactory.getLogger(AsyncResponseAdapter.class);

    private static final ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(new DefaultThreadFactory("mutimeoutwatcher"));

    private final AsyncHandle asyncHandle;
    private final Consumer resultConsumer;
    private volatile boolean isSuspended;
    private volatile boolean isCancelled;
    private volatile boolean isDone;
    private volatile @Nullable ScheduledFuture<?> cancelEvent;
    private volatile @Nullable TimeoutHandler timeoutHandler;
    private final List<ConnectionCallback> connectionCallbacks = new ArrayList<>();
    private final List<CompletionCallback> completionCallbacks = new ArrayList<>();
    private @Nullable Throwable exceptionWhileWriting;

    AsyncResponseAdapter(AsyncHandle asyncHandle, Consumer resultConsumer) {
        this.asyncHandle = asyncHandle;
        isSuspended = true;
        isCancelled = false;
        isDone = false;
        this.resultConsumer = resultConsumer;
        asyncHandle.addResponseCompleteHandler(this);
    }

    @Override
    public boolean resume(@Nullable Object response) {
        ScheduledFuture<?> event = cancelEvent;
        if (event != null) {
            isCancelled = isCancelled || event.cancel(false);
            cancelEvent = null;
        }
        if (isSuspended) {
            isSuspended = false;
            try {
                resultConsumer.accept(response);
                asyncHandle.complete();
            } catch (Exception e) {
                exceptionWhileWriting = e;
                asyncHandle.complete(e);
            } finally {
                isDone = true;
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean resume(Throwable response) {
        return resume((Object) Objects.requireNonNull(response, "response"));
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
        Response.ResponseBuilder resp = Response.status(503);
        if (retryAfterValue != null) {
            resp.header(HeaderNames.RETRY_AFTER.toString(), retryAfterValue);
        }
        return resume(resp.build());
    }

    @Override
    public boolean isSuspended() {
        return isSuspended;
    }

    @Override
    public boolean isCancelled() {
        return isCancelled;
    }

    @Override
    public boolean isDone() {
        return isDone;
    }

    @Override
    public boolean setTimeout(long time, TimeUnit unit) {
        Objects.requireNonNull(unit, "unit");
        if (!isSuspended) {
            return false;
        }
        if (cancelEvent != null) {
            cancelEvent.cancel(false);
        }
        cancelEvent = ses.schedule(() -> {
            TimeoutHandler th = this.timeoutHandler;
            if (th == null) {
                resume(new WebApplicationException(Response.status(503)
                    .type(MediaType.TEXT_HTML_TYPE)
                    .entity("<h1>503 Service Unavailable</h1><p>Timed out</p>").build()));
            } else {
                th.handleTimeout(this);
            }
        }, time, unit);
        return true;
    }

    @Override
    public void setTimeoutHandler(TimeoutHandler handler) {
        this.timeoutHandler = Objects.requireNonNull(handler, "handler");
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
        if (callback instanceof ConnectionCallback) {
            added.add(ConnectionCallback.class);
            connectionCallbacks.add((ConnectionCallback) callback);
        }
        if (callback instanceof CompletionCallback) {
            added.add(CompletionCallback.class);
            completionCallbacks.add((CompletionCallback) callback);
        }
        return added;
    }

    @Override
    public Map<Class<?>, Collection<Class<?>>> register(Object callback, Object... callbacks) {
        Objects.requireNonNull(callback, "callback");
        Objects.requireNonNull(callbacks, "callbacks");
        Map<Class<?>, Collection<Class<?>>> added = new HashMap<>();
        register(callback, added);
        for (Object cb : callbacks) {
            register(Objects.requireNonNull(cb, "callbacks element"), added);
        }
        return added;
    }

    private void register(Object callback, Map<Class<?>, Collection<Class<?>>> added) {
        Collection<Class<?>> registered = register(callback);
        Class<?> callbackClass = callback.getClass();
        if (!added.containsKey(callbackClass)) {
            added.put(callbackClass, new HashSet<>());
        }
        added.get(callbackClass).addAll(registered);
    }

    @Override
    public void onComplete(ResponseInfo info) {
        if (!info.completedSuccessfully()) {
            for (ConnectionCallback connectionCallback : connectionCallbacks) {
                try {
                    connectionCallback.onDisconnect(this);
                } catch (Exception e) {
                    log.warn("Exception from calling onDisconnect on " + connectionCallback);
                }
            }
        }
        for (CompletionCallback completionCallback : completionCallbacks) {
            try {
                completionCallback.onComplete(exceptionWhileWriting);
            } catch (Exception e) {
                log.warn("Exception from calling onComplete on " + completionCallback);
            }
        }
    }

    interface Consumer {
        void accept(@Nullable Object response) throws Exception;
    }
}
