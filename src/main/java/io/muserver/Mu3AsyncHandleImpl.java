package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class Mu3AsyncHandleImpl implements AsyncHandle {
    private final Mu3Request request;
    private final BaseResponse response;
    private CompletableFuture<@Nullable Void> responseFuture = CompletableFuture.completedFuture(null);
    private final CompletableFuture<@Nullable Void> completionFuture = new CompletableFuture<>();
    private final Lock lock = new ReentrantLock();
    // Guarded by lock. This is the terminal gate for response writes and exchange completion.
    private boolean completionRequested;
    private final Mu3ServerImpl server;
    private final Executor asyncExecutor;

    Mu3AsyncHandleImpl(Mu3Request request, BaseResponse response, Mu3ServerImpl server) {
        this.request = request;
        this.response = response;
        this.server = server;
        this.asyncExecutor = server::executeAsyncApplicationTask;
    }

    CompletableFuture<@Nullable Void> exchangeCompletion() {
        CompletableFuture<@Nullable Void> exchangeCompletion = new CompletableFuture<>();
        completionFuture.whenComplete((ignored, completionFailure) -> {
            if (completionFailure != null) {
                exchangeCompletion.completeExceptionally(completionCause(completionFailure));
                return;
            }
            CompletableFuture<@Nullable Void> writes;
            lock.lock();
            try {
                writes = responseFuture;
            } finally {
                lock.unlock();
            }
            writes.whenComplete((writeIgnored, writeFailure) -> {
                if (writeFailure == null) {
                    exchangeCompletion.complete(null);
                } else {
                    exchangeCompletion.completeExceptionally(completionCause(writeFailure));
                }
            });
        });
        return exchangeCompletion;
    }

    boolean completionIsPending() {
        lock.lock();
        try {
            return !completionRequested;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void setReadListener(RequestBodyListener readListener) {
        Objects.requireNonNull(readListener, "readListener");
        // Claim body ownership before asynchronous dispatch so a blocking read
        // or second listener cannot race the first reader task.
        new AsyncBodyReader(readListener, request.body()).scheduleNextRead();
    }

    private final class AsyncBodyReader {
        private final RequestBodyListener readListener;
        private final byte[] buffer = new byte[8192];
        private final AtomicBoolean finished = new AtomicBoolean();
        private final InputStream clientIn;

        private AsyncBodyReader(
            RequestBodyListener readListener,
            InputStream clientIn
        ) {
            this.readListener = readListener;
            this.clientIn = clientIn;
        }

        private void scheduleNextRead() {
            if (finished.get()) {
                return;
            }
            try {
                server.executeAsyncApplicationTask(this::readNext);
            } catch (RejectedExecutionException rejected) {
                fail(rejected, false);
            }
        }

        private void readNext() {
            if (finished.get()) {
                return;
            }
            final int read;
            try {
                read = clientIn.read(buffer);
            } catch (Throwable t) {
                fail(t, true);
                return;
            }
            if (read == -1) {
                finishReading();
                return;
            }
            if (read == 0) {
                scheduleNextRead();
                return;
            }

            var callbackUsed = new AtomicBoolean();
            try {
                readListener.onDataReceived(ByteBuffer.wrap(buffer, 0, read), error -> {
                    if (!callbackUsed.compareAndSet(false, true)) {
                        return;
                    }
                    if (error == null) {
                        scheduleNextRead();
                    } else {
                        fail(error, false);
                    }
                });
            } catch (Throwable t) {
                // onDataReceived runs in the async application domain, so its
                // documented error callback belongs to the same turn.
                fail(t, true);
            }
        }

        private void finishReading() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                readListener.onComplete();
            } catch (Throwable t) {
                complete(t);
            } finally {
                Mutils.closeSilently(clientIn);
            }
        }

        private void fail(Throwable failure, boolean notifyListener) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            try {
                if (notifyListener) {
                    readListener.onError(failure);
                }
            } catch (Throwable listenerFailure) {
                addSuppressedIfDifferent(failure, listenerFailure);
            } finally {
                Mutils.closeSilently(clientIn);
            }
            complete(failure);
        }
    }

    @Override
    public void complete() {
        completeOnce(null);
    }

    @Override
    public void complete(@Nullable Throwable throwable) {
        completeOnce(throwable);
    }

    private void completeOnce(@Nullable Throwable failure) {
        lock.lock();
        try {
            if (completionRequested) {
                return;
            }
            completionRequested = true;
        } finally {
            lock.unlock();
        }
        if (failure == null) {
            completionFuture.complete(null);
        } else {
            completionFuture.completeExceptionally(failure);
        }
    }

    @Override
    public void write(ByteBuffer data, DoneCallback callback) {
        var taskStarted = new AtomicBoolean();
        @Nullable CompletableFuture<@Nullable Void> writeFuture;
        lock.lock();
        try {
            if (completionRequested) {
                writeFuture = null;
            } else {
                responseFuture = responseFuture.thenRunAsync(() -> {
                    taskStarted.set(true);
                    try {
                        copyBufferToResponseOutput(data);
                    } catch (Throwable e) {
                        try {
                            callback.onComplete(e);
                        } catch (Throwable callbackFailure) {
                            addSuppressedIfDifferent(e, callbackFailure);
                        }
                        complete(e);
                        throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException("Error while writing body", e);
                    }
                    try {
                        callback.onComplete(null);
                    } catch (Throwable e) {
                        complete(e);
                        throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException("Error in write callback", e);
                    }
                }, asyncExecutor).thenApply(ignored -> null);
                writeFuture = responseFuture;
            }
        } finally {
            lock.unlock();
        }
        if (writeFuture == null) {
            Throwable failure = completedResponseWriteFailure();
            try {
                server.executeAsyncApplicationTask(() ->
                    invokeWriteCallback(callback, failure)
                );
            } catch (RejectedExecutionException rejected) {
                // The callback contract still needs an outcome when the
                // required async executor cannot accept the notification.
                invokeWriteCallback(callback, failure);
            }
            return;
        }
        writeFuture.whenComplete((ignored, failure) -> {
            if (failure != null && !taskStarted.get()) {
                Throwable cause = completionCause(failure);
                try {
                    callback.onComplete(cause);
                } catch (Throwable callbackFailure) {
                    addSuppressedIfDifferent(cause, callbackFailure);
                }
                complete(cause);
            }
        });
    }

    @Override
    public Future<@Nullable Void> write(ByteBuffer data) {
        var taskStarted = new AtomicBoolean();
        CompletableFuture<@Nullable Void> writeFuture;
        lock.lock();
        try {
            if (completionRequested) {
                return CompletableFuture.failedFuture(completedResponseWriteFailure());
            }
            writeFuture = responseFuture.thenRunAsync(() -> {
                taskStarted.set(true);
                try {
                    copyBufferToResponseOutput(data);
                } catch (Throwable e) {
                    complete(e);
                    throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException("Error while writing body", e);
                }
            }, asyncExecutor).thenApply(ignored -> null);
            responseFuture = writeFuture;
        } finally {
            lock.unlock();
        }
        writeFuture.whenComplete((ignored, failure) -> {
            if (failure != null && !taskStarted.get()) {
                complete(completionCause(failure));
            }
        });
        return writeFuture;
    }

    private static void invokeWriteCallback(DoneCallback callback, Throwable failure) {
        try {
            callback.onComplete(failure);
        } catch (Throwable callbackFailure) {
            addSuppressedIfDifferent(failure, callbackFailure);
        }
    }

    private static IllegalStateException completedResponseWriteFailure() {
        return new IllegalStateException("The asynchronous response is already complete");
    }

    @Override
    public void executeApplicationTask(Runnable task) {
        Objects.requireNonNull(task, "task");
        RejectedExecutionException rejected = server.tryExecuteHandlerTask(task);
        if (rejected != null) {
            throw rejected;
        }
    }

    @Override
    public Future<?> scheduleApplicationTask(Runnable task, long delay, TimeUnit unit) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(unit, "unit");
        return server.scheduleTimerCallback(() -> {
            try {
                executeApplicationTask(task);
            } catch (RejectedExecutionException rejected) {
                complete(rejected);
            }
        }, delay, unit);
    }

    private static Throwable completionCause(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
            && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    @SuppressWarnings("ReferenceEquality") // Throwable forbids suppressing itself; identity is required.
    private static void addSuppressedIfDifferent(Throwable failure, Throwable suppressed) {
        if (failure != suppressed) {
            failure.addSuppressed(suppressed);
        }
    }

    private void copyBufferToResponseOutput(ByteBuffer data) throws IOException {
        int len = data.remaining();
        int pos = data.position();
        OutputStream respOut = response.outputStream();
        if (data.hasArray()) {
            respOut.write(data.array(), data.arrayOffset() + pos, len);
        } else {
            var buffer = new byte[len];
            data.get(buffer);
            respOut.write(buffer);
        }
        respOut.flush();
    }

    @Override
    public void addResponseCompleteHandler(ResponseCompleteListener responseCompleteListener) {
        response.addCompletionListener(responseCompleteListener);
    }
}
