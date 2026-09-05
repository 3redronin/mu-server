package io.muserver;

import io.muserver.internal.FatalErrors;

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

class Mu3AsyncHandleImpl implements AsyncHandle, io.muserver.internal.AsyncExecution {
    private final Mu3Request request;
    private final BaseResponse response;
    private final AsyncResponseOutput output;
    private final Mu3ServerImpl server;
    private final SerialApplicationTasks callbacks;

    Mu3AsyncHandleImpl(Mu3Request request, BaseResponse response, Mu3ServerImpl server) {
        this.request = request;
        this.response = response;
        this.server = server;
        this.callbacks = new SerialApplicationTasks(server);
        this.output = new AsyncResponseOutput(server::executeInternalTask,
            this::copyBufferToResponseOutput, response::abortAsyncOutput, callbacks);
    }

    CompletableFuture<@Nullable Void> exchangeCompletion() { return output.completion(); }

    boolean completionIsPending() { return output.completionIsPending(); }

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
                server.executeInternalTask(this::readNext);
            } catch (RejectedExecutionException rejected) {
                fail(rejected, true);
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
                FatalErrors.rethrow(t);
                return;
            }
            if (read == -1) {
                callbacks.submit(this::finishReading, rejected -> fail(rejected, false));
                return;
            }
            if (read == 0) {
                scheduleNextRead();
                return;
            }

            callbacks.submit(() -> deliver(read), rejected -> fail(rejected, false));
        }

        private void deliver(int read) {
            if (finished.get()) return;
            var callbackUsed = new AtomicBoolean();
            try {
                readListener.onDataReceived(ByteBuffer.wrap(buffer, 0, read), error -> {
                    if (!callbackUsed.compareAndSet(false, true)) return;
                    if (error == null) scheduleNextRead();
                    else fail(error, false);
                });
            } catch (Throwable failure) {
                fail(failure, true);
                FatalErrors.rethrow(failure);
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
                FatalErrors.rethrow(t);
            } finally {
                Mutils.closeSilently(clientIn);
            }
        }

        private void fail(Throwable failure, boolean notifyListener) {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            Mutils.closeSilently(clientIn);
            if (notifyListener) {
                callbacks.submit(() -> {
                    try {
                        readListener.onError(failure);
                    } catch (Throwable listenerFailure) {
                        addSuppressedIfDifferent(failure, listenerFailure);
                        FatalErrors.rethrow(listenerFailure);
                    } finally {
                        complete(failure);
                    }
                }, thisFailure -> complete(failure));
            } else {
                complete(failure);
            }
        }
    }

    @Override public void complete() { output.complete(null); }

    @Override public void complete(@Nullable Throwable throwable) { output.complete(throwable); }

    @Override public void write(ByteBuffer data, DoneCallback callback) {
        output.write(data, Objects.requireNonNull(callback, "callback"));
    }

    @Override public Future<@Nullable Void> write(ByteBuffer data) { return output.write(data, null); }

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
