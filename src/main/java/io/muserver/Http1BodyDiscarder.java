package io.muserver;

import org.jspecify.annotations.Nullable;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Exclusive post-handler body cleanup. Input waits retain the exchange, not an internal worker. */
final class Http1BodyDiscarder {
    private final Mu3ServerImpl server;
    private final Http1BodyStream body;
    private final AsyncBodyInput input;
    private final boolean throwIfTooBig;
    private final CompletableFuture<Http1BodyStream.State> completion = new CompletableFuture<>();
    private volatile @Nullable Throwable readFailure;
    private volatile boolean timedOut;

    Http1BodyDiscarder(Mu3ServerImpl server, Http1BodyStream body, boolean throwIfTooBig) {
        this.server = server;
        this.body = body;
        this.input = Objects.requireNonNull(body.asynchronousInput());
        this.throwIfTooBig = throwIfTooBig;
    }

    /** Called on an internal worker after application/body-reader ownership has returned. */
    CompletableFuture<Http1BodyStream.State> start() {
        advance();
        return completion;
    }

    private void advance() {
        try {
            Throwable failure = readFailure;
            if (failure != null) throw failure;
            if (timedOut) {
                // The blocking discard treats a read timeout as failed cleanup, not a new response.
                body.discardFailed();
                completion.complete(body.state());
                return;
            }
            Http1BodyStream.State state = body.discardAvailable(throwIfTooBig);
            if (state != Http1BodyStream.State.DISCARDING) {
                completion.complete(state);
                return;
            }
            InputWait waiting = new InputWait(input.whenReadable());
            waiting.start(input.readTimeoutMillis(),
                (task, timeout) -> server.scheduleTimerCallback(task, timeout, TimeUnit.MILLISECONDS),
                (error, expired) -> {
                    readFailure = error;
                    timedOut = expired;
                    try { server.executeInternalTask(this::advance); }
                    catch (RejectedExecutionException rejected) {
                        // Completion can dispatch application notifications through a caller-runs executor.
                        Thread cleanup = new Thread(() -> fail(rejected), "mu-body-rejected-discard");
                        cleanup.setDaemon(true);
                        cleanup.start();
                    }
                });
        } catch (Throwable failure) {
            fail(failure);
            // The exchange unwraps Errors after its mandatory retirement bookkeeping. In
            // particular, the initial inline turn must return its future even on failure.
        }
    }

    private void fail(Throwable failure) {
        body.discardFailed();
        completion.completeExceptionally(failure);
    }
}
