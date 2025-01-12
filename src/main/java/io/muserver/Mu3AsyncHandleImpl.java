package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class Mu3AsyncHandleImpl implements AsyncHandle {
    private final Mu3Request request;
    private final BaseResponse response;
    private CompletableFuture<@Nullable Void> responseFuture = CompletableFuture.completedFuture(null);
    private final CompletableFuture<@Nullable Void> completionFuture = new CompletableFuture<>();
    private final Lock lock = new ReentrantLock();

    Mu3AsyncHandleImpl(Mu3Request request, BaseResponse response) {
        this.request = request;
        this.response = response;
    }

    public void waitForCompletion(long timeoutMillis) throws Throwable {
        try {
            var before = System.currentTimeMillis();
            completionFuture.get(timeoutMillis, TimeUnit.MILLISECONDS);
            var duration = System.currentTimeMillis() - before;
            var newTimeout = timeoutMillis - duration;
            if (newTimeout <= 0) throw new TimeoutException("Timeout on completion future");
            try {
                lock.lock();
                responseFuture.get(newTimeout, TimeUnit.MILLISECONDS);
            } finally {
                lock.unlock();
            }
        } catch (ExecutionException e) {
            throw e.getCause();
        }
    }

    @Override
    public void setReadListener(RequestBodyListener readListener) {
        var requestFuture = CompletableFuture.runAsync(() -> {
            var requestException = new AtomicReference<@Nullable Throwable>();
            try (var clientIn = request.body()) {
                var buffer = new byte[8192];
                int read;
                while (requestException.get() == null) {
                    try {
                        read = clientIn.read(buffer);
                    } catch (Throwable e) {
                        requestException.set(e);
                        readListener.onError(e);
                        break;
                    }
                    if (read == -1) {
                        break;
                    }
                    if (read > 0) {
                        var latch = new CountDownLatch(1);
                        DoneCallback dc = error -> {
                            if (error != null) {
                                requestException.set(error);
                            }
                            latch.countDown();
                        };
                        readListener.onDataReceived(ByteBuffer.wrap(buffer, 0, read), dc);
                        // TODO set proper timeout
                        if (!latch.await(24, TimeUnit.HOURS)) {
                            requestException.set(new TimeoutException("Timed out in read callback " + readListener));
                        }
                    }
                }
                var toThrow = requestException.get();
                if (toThrow != null) {
                    throw toThrow;
                } else {
                    readListener.onComplete();
                }
            } catch (Throwable e) {
                requestException.set(e);
            }
            var e = requestException.get();
            if (e != null) {
                throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException("Error while reading body", e);
            }
        });
        requestFuture.exceptionally( t -> {
            complete(t);
            return null;
        });
    }

    @Override
    public void complete() {
        if (!completionFuture.isDone()) {
            completionFuture.complete(null);
        }
    }

    @Override
    public void complete(@Nullable Throwable throwable) {
        if (throwable == null) {
            complete();
        } else {
            if (!completionFuture.isDone()) {
                completionFuture.completeExceptionally(throwable);
            }
        }
    }

    @Override
    public void write(ByteBuffer data, DoneCallback callback) {
        try {
            lock.lock();
            responseFuture = responseFuture.thenRunAsync(() -> {
                try {
                    copyBufferToResponseOutput(data);
                    callback.onComplete(null);
                } catch (Throwable e) {
                    try {
                        callback.onComplete(e);
                    } catch (Exception ignored) {
                    }
                    complete(e);
                    throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException("Error while writing body", e);
                }
            });
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Future<Void> write(ByteBuffer data) {
        try {
            lock.lock();
            var writeFuture = responseFuture.thenRunAsync(() -> {
                try {
                    copyBufferToResponseOutput(data);
                } catch (Throwable e) {
                    complete(e);
                    throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException("Error while writing body", e);
                }
            });
            responseFuture = writeFuture;
            return writeFuture;
        } finally {
            lock.unlock();
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
