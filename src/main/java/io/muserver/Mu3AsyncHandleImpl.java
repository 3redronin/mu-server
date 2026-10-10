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

class Mu3AsyncHandleImpl implements AsyncHandle, io.muserver.internal.AsyncExecution {
    private final Mu3Request request;
    private final BaseResponse response;
    private final AsyncResponseOutput output;
    private final Mu3ServerImpl server;
    private final SerialApplicationTasks callbacks;
    private final Object bodyReaderLock = new Object();
    private @Nullable AsyncBodyReader bodyReader;
    private final CompletableFuture<@Nullable Void> exchangeFinished = new CompletableFuture<>();

    Mu3AsyncHandleImpl(Mu3Request request, BaseResponse response, Mu3ServerImpl server) {
        this.request = request;
        this.response = response;
        this.server = server;
        this.callbacks = new SerialApplicationTasks(server);
        AsyncResponseOutput.AsyncWriter writer = response.asynchronousWriter();
        this.output = writer == null ? new AsyncResponseOutput(server::executeInternalTask,
            this::copyBufferToResponseOutput, response::abortAsyncOutput, callbacks)
            : AsyncResponseOutput.asynchronous(server::executeInternalTask, writer, response::abortAsyncOutput, callbacks);
        output.completion().whenComplete((ignored, failure) -> {
            AsyncBodyReader reader;
            synchronized (bodyReaderLock) { reader = bodyReader; }
            CompletableFuture<?> bodyStopped = reader == null ? CompletableFuture.completedFuture(null) : reader.stop();
            bodyStopped.whenComplete((unused, bodyFailure) -> {
                synchronized (bodyReaderLock) { bodyReader = null; }
                Throwable terminal = failure == null ? bodyFailure : failure;
                publishExchangeCompletion(terminal);
            });
        });
    }

    /** A peer reset can finish an idle output queue on the protocol coordinator itself. */
    private void publishExchangeCompletion(@Nullable Throwable failure) {
        Runnable publish = () -> {
            if (failure == null) exchangeFinished.complete(null);
            else exchangeFinished.completeExceptionally(failure);
        };
        try { server.executeInternalTask(publish); }
        catch (RuntimeException | Error rejected) {
            // A caller-runs application executor can execute response cleanup while submitting it.
            // Keep that submission off the notifier even when normal continuation dispatch fails.
            Thread cleanup = new Thread(() -> {
                if (failure == null) exchangeFinished.completeExceptionally(rejected);
                else {
                    @SuppressWarnings("ReferenceEquality")
                    boolean different = failure != rejected;
                    if (different) failure.addSuppressed(rejected);
                    exchangeFinished.completeExceptionally(failure);
                }
                FatalErrors.rethrow(rejected);
            }, "mu-rejected-exchange-completion");
            cleanup.setDaemon(true);
            cleanup.start();
        }
    }

    CompletableFuture<@Nullable Void> exchangeCompletion() { return exchangeFinished; }

    boolean completionIsPending() { return output.completionIsPending(); }

    @Override
    public void setReadListener(RequestBodyListener readListener) {
        Objects.requireNonNull(readListener, "readListener");
        // Claim body ownership before asynchronous dispatch so a blocking read
        // or second listener cannot race the first reader task.
        AsyncBodyReader reader;
        synchronized (bodyReaderLock) {
            if (!output.completionIsPending()) throw new IllegalStateException("The asynchronous response is already complete");
            reader = new AsyncBodyReader(readListener, request.body());
            bodyReader = reader;
        }
        reader.scheduleNextRead();
    }

    private final class AsyncBodyReader {
        private final RequestBodyListener readListener;
        private final byte[] buffer = new byte[8192];
        private final AtomicBoolean finished = new AtomicBoolean();
        private final InputStream clientIn;
        private final @Nullable AsyncBodyInput asynchronousInput;
        private final Object ownership = new Object();
        private final CompletableFuture<Void> stopped = new CompletableFuture<>();
        // Guarded by ownership. A read task may also dispatch an inline application callback.
        private int activeReads;
        private int pendingCallbacks;
        private boolean stopRequested;
        private boolean stopInitialized;
        private @Nullable Throwable stopFailure;
        private @Nullable InputWait waiting;
        private @Nullable Throwable readFailure;
        private boolean readTimedOut;

        private AsyncBodyReader(
            RequestBodyListener readListener,
            InputStream clientIn
        ) {
            this.readListener = readListener;
            this.clientIn = clientIn;
            this.asynchronousInput = clientIn instanceof AsyncBodyInput.Provider
                ? ((AsyncBodyInput.Provider) clientIn).asynchronousInput() : null;
        }

        private void scheduleNextRead() {
            if (finished.get()) {
                return;
            }
            try {
                server.executeInternalTask(this::readNext);
            } catch (RejectedExecutionException rejected) {
                // Readiness and timer notifications may originate on transport threads. Even a
                // supplied CallerRuns executor must never put application callbacks on those threads.
                Thread cleanup = new Thread(() -> fail(rejected, true), "mu-body-rejected-reader");
                cleanup.setDaemon(true);
                cleanup.start();
            }
        }

        private void readNext() {
            synchronized (ownership) {
                if (finished.get()) return;
                activeReads++;
            }
            try { readAndDispatch(); }
            finally {
                synchronized (ownership) { activeReads--; }
                finishStopIfIdle();
            }
        }

        private void readAndDispatch() {
            final int read;
            try {
                Throwable failure;
                boolean timedOut;
                synchronized (ownership) {
                    failure = readFailure;
                    timedOut = readTimedOut;
                    readFailure = null;
                    readTimedOut = false;
                }
                if (failure != null) throw failure;
                AsyncBodyInput input = asynchronousInput;
                if (timedOut) throw Objects.requireNonNull(input).timeoutFailure();
                read = input == null ? clientIn.read(buffer) : input.readAvailable(buffer);
                if (read == 0 && input != null) {
                    awaitInput(input);
                    return;
                }
            } catch (Throwable t) {
                fail(t, true);
                FatalErrors.rethrow(t);
                return;
            }
            if (read == -1) {
                dispatchCallback(this::finishReading);
                return;
            }
            if (read == 0) {
                scheduleNextRead();
                return;
            }

            dispatchCallback(() -> deliver(read));
        }

        private void awaitInput(AsyncBodyInput input) {
            InputWait next = new InputWait(input.whenReadable());
            long timeoutMillis = input.readTimeoutMillis();
            synchronized (ownership) {
                if (!finished.get()) waiting = next;
            }
            if (finished.get()) {
                next.cancel();
                return;
            }
            next.start(timeoutMillis, (task, timeout) -> server.scheduleTimerCallback(task, timeout, TimeUnit.MILLISECONDS),
                (failure, timedOut) -> resume(next, failure, timedOut));
        }

        @SuppressWarnings("ReferenceEquality") // Only the currently owned wait can resume this reader.
        private void resume(InputWait ready, @Nullable Throwable failure, boolean timedOut) {
            synchronized (ownership) {
                if (waiting != ready || finished.get()) return;
                waiting = null;
                readFailure = failure;
                readTimedOut = timedOut;
            }
            scheduleNextRead();
        }

        private CompletableFuture<Void> stop() {
            InputWait pending;
            synchronized (ownership) {
                if (stopRequested) return stopped;
                stopRequested = true;
                // A late data acknowledgement cannot refill its buffer or resume parser access.
                finished.set(true);
                pending = waiting;
                waiting = null;
            }
            try {
                if (pending != null) pending.cancel();
                if (clientIn instanceof Http2BodyInputStream) {
                    // H2 has a separate frame producer: return queued credit and wake a waiting reader.
                    ((Http2BodyInputStream) clientIn).discardRemaining();
                }
            } catch (RuntimeException failure) {
                synchronized (ownership) { stopFailure = failure; }
            } finally {
                synchronized (ownership) { stopInitialized = true; }
                finishStopIfIdle();
            }
            return stopped;
        }

        private void finishStopIfIdle() {
            boolean done;
            Throwable failure;
            synchronized (ownership) {
                done = stopInitialized && activeReads == 0 && pendingCallbacks == 0;
                failure = stopFailure;
            }
            if (done) {
                if (failure == null) stopped.complete(null);
                else stopped.completeExceptionally(failure);
            }
        }

        private void dispatchCallback(Runnable callback) {
            dispatchCallback(callback, rejected -> fail(rejected, false));
        }

        private void dispatchCallback(Runnable callback, java.util.function.Consumer<RejectedExecutionException> rejectedCallback) {
            synchronized (ownership) {
                if (stopRequested) return;
                pendingCallbacks++;
            }
            var released = new AtomicBoolean();
            Runnable release = () -> {
                if (!released.compareAndSet(false, true)) return;
                synchronized (ownership) { pendingCallbacks--; }
                finishStopIfIdle();
            };
            callbacks.submit(() -> {
                try {
                    boolean invoke;
                    synchronized (ownership) { invoke = !stopRequested; }
                    if (invoke) callback.run();
                } finally { release.run(); }
            }, rejected -> {
                try { rejectedCallback.accept(rejected); }
                finally { release.run(); }
            });
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
                dispatchCallback(() -> {
                    try {
                        readListener.onError(failure);
                    } catch (Throwable listenerFailure) {
                        addSuppressedIfDifferent(failure, listenerFailure);
                        FatalErrors.rethrow(listenerFailure);
                    } finally {
                        complete(failure);
                    }
                }, rejected -> complete(failure));
            } else {
                complete(failure);
            }
        }
    }

    @Override public void complete() { output.complete(null); }

    @Override public void complete(@Nullable Throwable throwable) { output.complete(throwable); }

    @Override public void write(ByteBuffer data, DoneCallback callback) {
        writeWithCallback(data, callback);
    }

    Future<@Nullable Void> writeWithCallback(ByteBuffer data, DoneCallback callback) {
        return output.write(data, Objects.requireNonNull(callback, "callback"));
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
        copyBufferToOutput(data, response.outputStream());
    }

    static void copyBufferToOutput(ByteBuffer data, OutputStream respOut) throws IOException {
        int len = data.remaining();
        int pos = data.position();
        if (data.hasArray()) {
            respOut.write(data.array(), data.arrayOffset() + pos, len);
        } else {
            var buffer = new byte[Math.min(len, 8192)];
            while (data.hasRemaining()) {
                int count = Math.min(data.remaining(), buffer.length);
                data.get(buffer, 0, count);
                respOut.write(buffer, 0, count);
            }
        }
        respOut.flush();
    }

    @Override
    public void addResponseCompleteHandler(ResponseCompleteListener responseCompleteListener) {
        response.addCompletionListener(responseCompleteListener);
    }
}
