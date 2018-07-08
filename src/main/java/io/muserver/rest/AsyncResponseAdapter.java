package io.muserver.rest;

import io.muserver.AsyncHandle;
import io.muserver.HeaderNames;
import io.muserver.Mutils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.CompletionCallback;
import javax.ws.rs.container.ConnectionCallback;
import javax.ws.rs.container.TimeoutHandler;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

class AsyncResponseAdapter implements AsyncResponse, AsyncHandle.ResponseCompletedListener {
    private static final Logger log = LoggerFactory.getLogger(AsyncResponseAdapter.class);

    private static ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor();

    private final AsyncHandle asyncHandle;
    private final Consumer resultConsumer;
    private volatile boolean isSuspended;
    private volatile boolean isCancelled;
    private volatile boolean isDone;
    private volatile ScheduledFuture<?> cancelEvent;
    private volatile TimeoutHandler timeoutHandler;
    private final List<ConnectionCallback> connectionCallbacks = new ArrayList<>();
    private final List<CompletionCallback> completionCallbacks = new ArrayList<>();
    private Throwable exceptionWhileWriting = null;

    AsyncResponseAdapter(AsyncHandle asyncHandle, Consumer resultConsumer) {
        this.asyncHandle = asyncHandle;
        isSuspended = true;
        isCancelled = false;
        isDone = false;
        this.resultConsumer = resultConsumer;
        asyncHandle.setResponseCompletedHandler(this);
    }

    @Override
    public boolean resume(Object response) {
        if (cancelEvent != null) {
            isCancelled = isCancelled || cancelEvent.cancel(false);
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
        return resume((Object) response);
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
        return doCancel(Mutils.toHttpDate(retryAfter));
    }

    private boolean doCancel(Object retryAfterValue) {
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
                    .type(MediaType.TEXT_PLAIN_TYPE)
                    .entity("<h1>503 Service Unavailable</h1><p>Timed out</p>").build()));
            } else {
                th.handleTimeout(this);
            }
        }, time, unit);
        return true;
    }

    @Override
    public void setTimeoutHandler(TimeoutHandler handler) {
        this.timeoutHandler = handler;
    }

    @Override
    public Collection<Class<?>> register(Class<?> callback) {
        throw new NotImplementedException("Mu-Server does not instantiate classes for you. Please use register(Object) with an instantiated callback instead.");
    }

    @Override
    public Map<Class<?>, Collection<Class<?>>> register(Class<?> callback, Class<?>... callbacks) {
        throw new NotImplementedException("Mu-Server does not instantiate classes for you. Please use register(Object, Object...) with instantiated callbacks instead.");
    }

    @Override
    public Collection<Class<?>> register(Object callback) {
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
        Map<Class<?>, Collection<Class<?>>> added = new HashMap<>();
        register(callback, added);
        for (Object cb : callbacks) {
            register(cb, added);
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
    public void onComplete(boolean responseWasCompleted) {
        if (!responseWasCompleted) {
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
        void accept(Object response) throws Exception;
    }
}
