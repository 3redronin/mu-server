package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/** Runs existing stream encoders in bounded input turns and joins each owned output borrow. */
final class AsyncResponseRenderer {
    @FunctionalInterface interface Operation { void run() throws Throwable; }
    @FunctionalInterface private interface Step { boolean render(ResponseOutputCapture.Capture capture) throws Throwable; }
    private final Executor executor;
    private final ResponseOutputCapture output;
    private final Supplier<OutputStream> responseStream;

    AsyncResponseRenderer(Executor executor, ResponseOutputCapture output, Supplier<OutputStream> responseStream) {
        this.executor = executor;
        this.output = output;
        this.responseStream = responseStream;
    }

    CompletableFuture<@Nullable Void> write(ByteBuffer source) {
        return write(source, null);
    }

    CompletableFuture<@Nullable Void> write(ByteBuffer source, @Nullable Operation preparation) {
        // Preserve the public adapter's existing position behavior for each buffer kind.
        ByteBuffer input = source.hasArray() ? source.duplicate() : source;
        int initialPosition = input.position();
        byte[] copy = input.hasArray() ? new byte[0] : new byte[Math.min(input.remaining(), ResponseWriteSizes.MAX_SOURCE_BYTES)];
        return start(capture -> {
            OutputStream stream = responseStream.get();
            // The first ordinary turn empties any eligible BufferedOutputStream prefix without
            // making a mixed capture spill merely because this input supports larger borrows.
            int count = input.hasArray() && input.position() != initialPosition && output.multiFrameBorrowAllowed()
                ? Math.min(input.remaining(), output.maxSourceBorrowBytes())
                : output.inputHeadroomUseful() ? ResponseWriteSizes.nextFragmentBytes(input.remaining())
                : Math.min(input.remaining(), ResponseWriteSizes.MAX_SOURCE_BYTES);
            if (input.hasArray()) {
                int offset = input.arrayOffset() + input.position();
                capture.borrowSourceIfUnchanged(input.array(), offset, count);
                stream.write(input.array(), offset, count);
                input.position(input.position() + count);
            } else if (count > 0) {
                input.get(copy, 0, count);
                capture.borrowSourceIfUnchanged(copy, 0, count);
                stream.write(copy, 0, count);
            }
            if (input.hasRemaining()) return false;
            stream.flush();
            return true;
        }, preparation);
    }

    CompletableFuture<@Nullable Void> render(Operation operation) {
        return render(operation, null);
    }

    CompletableFuture<@Nullable Void> render(Operation operation, @Nullable Operation preparation) {
        return start(capture -> { operation.run(); return true; }, preparation);
    }

    private CompletableFuture<@Nullable Void> start(Step step, @Nullable Operation preparation) {
        Task task = new Task(step, preparation);
        task.run();
        return task.completion;
    }

    private final class Task implements Runnable {
        private final Step step;
        private final CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
        private ResponseOutputCapture.@Nullable Capture capture;
        private @Nullable CompletableFuture<@Nullable Void> pending;
        private @Nullable Throwable renderFailure;
        private @Nullable Operation preparation;
        private boolean rendered;

        private Task(Step step, @Nullable Operation preparation) { this.step = step; this.preparation = preparation; }

        @Override public void run() {
            try {
                for (int turn = 0; turn < 64; turn++) {
                    CompletableFuture<?> writing = pending;
                    if (writing != null) {
                        if (!writing.isDone()) {
                            // A notifier may dispatch the next turn immediately. Do not touch
                            // ownership after registering this continuation.
                            writing.whenComplete((ignored, failure) -> schedule());
                            return;
                        }
                        writing.join();
                        pending = null;
                    }
                    ResponseOutputCapture.Capture current = capture;
                    if (current != null) {
                        pending = current.writeNext();
                        if (pending != null) continue;
                        capture = null;
                        current.close();
                        if (renderFailure != null) { finishFailure(renderFailure); return; }
                    }
                    if (rendered) { completion.complete(null); return; }
                    current = output.begin();
                    capture = current;
                    try {
                        Operation initial = preparation;
                        preparation = null;
                        if (initial != null) initial.run();
                        else rendered = step.render(current);
                    }
                    catch (Throwable failure) { renderFailure = failure; }
                    finally {
                        try { current.finishRendering(); }
                        catch (Throwable failure) {
                            Throwable encoding = renderFailure;
                            if (encoding == null) throw failure;
                            suppress(encoding, failure);
                            throw encoding;
                        }
                    }
                    if (renderFailure instanceof VirtualMachineError || renderFailure instanceof ThreadDeath) {
                        // Fatal encoder failure must not wait for a peer to accept its prefix.
                        // This capture has not submitted any transport borrow yet.
                        finishFailure(renderFailure);
                        return;
                    }
                }
                CompletableFuture<?> writing = pending;
                if (writing != null && !writing.isDone()) writing.whenComplete((ignored, failure) -> schedule());
                else schedule();
            } catch (Throwable failure) {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                    ? failure.getCause() : failure;
                finishFailure(Objects.requireNonNull(cause));
            }
        }

        private void schedule() {
            try { executor.execute(this); }
            catch (RuntimeException | Error rejected) {
                // Both call sites have no outstanding borrow: a completed write notified us,
                // or this turn yielded between synchronous rendering/drain steps.
                Thread cleanup = new Thread(() -> finishFailure(rejected), "mu-response-render-rejected");
                cleanup.setDaemon(true);
                cleanup.start();
            }
        }

        private void finishFailure(Throwable failure) {
            ResponseOutputCapture.Capture current = capture;
            capture = null;
            if (current != null) {
                try { current.close(); }
                catch (Throwable cleanup) { suppress(failure, cleanup); }
            }
            Throwable encoding = renderFailure;
            if (encoding != null) suppress(failure, encoding);
            completion.completeExceptionally(failure);
        }
    }

    @SuppressWarnings("ReferenceEquality") // Throwable cannot suppress itself.
    private static void suppress(Throwable failure, Throwable diagnostic) {
        if (failure != diagnostic) failure.addSuppressed(diagnostic);
    }
}
