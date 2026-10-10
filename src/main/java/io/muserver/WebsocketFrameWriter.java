package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Serial frame output with one borrowed transport source at a time. Internal future listeners
 * and event hooks must only publish state or schedule work: application code cannot run while
 * the writer owns a turn. The caller closes/aborts the transport when finishing this writer.
 */
final class WebsocketFrameWriter {
    interface Events {
        void bytesSent(int count);
        void closeStarted();
        void closeWritten();
        void failed(IOException failure);
    }

    private static final int STEPS_PER_TURN = 64;
    private final Executor executor;
    private final AsyncTransportOutput output;
    private final Runnable abortTransport;
    private final Events events;
    private final WebsocketWriteState state = new WebsocketWriteState();
    private final ReentrantLock lock = new ReentrantLock();
    // Guarded by lock; frame encoding and active transport ownership belong to the serial turn.
    private final Queue<Write> queued = new ArrayDeque<>();
    private @Nullable IOException terminal;
    private @Nullable CompletableFuture<@Nullable Void> closeCompletion;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final CompletableFuture<Void> ended = new CompletableFuture<>();
    private volatile @Nullable CompletableFuture<@Nullable Void> pending;
    private @Nullable Write active;
    private WebsocketWriteState.@Nullable Frame frame;
    private boolean payloadWriting;
    private int pendingBytes;
    private boolean failureReported;
    private volatile boolean closeWritten;
    private volatile @Nullable IOException dispatchFailure;

    private static final class Write {
        final int opcode;
        final boolean fin;
        final boolean fragment;
        ByteBuffer payload;
        final @Nullable Supplier<@Nullable ByteBuffer> automaticPing;
        final CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();
        Write(int opcode, boolean fin, boolean fragment, ByteBuffer payload) {
            this(opcode, fin, fragment, payload, null);
        }
        Write(int opcode, boolean fin, boolean fragment, ByteBuffer payload,
              @Nullable Supplier<@Nullable ByteBuffer> automaticPing) {
            this.opcode = opcode;
            this.fin = fin;
            this.fragment = fragment;
            this.payload = payload.duplicate();
            this.automaticPing = automaticPing;
        }
    }

    WebsocketFrameWriter(Executor executor, AsyncTransportOutput output, Runnable abortTransport, Events events) {
        this.executor = executor;
        this.output = output;
        this.abortTransport = abortTransport;
        this.events = events;
    }

    CompletableFuture<@Nullable Void> write(int opcode, boolean fin, boolean fragment, ByteBuffer payload) {
        return enqueue(new Write(opcode, fin, fragment, payload));
    }

    /** Evaluate at the head of the queue so latency excludes preceding writes; null skips a late ping. */
    CompletableFuture<@Nullable Void> automaticPing(Supplier<@Nullable ByteBuffer> payload) {
        return enqueue(new Write(9, true, false, ByteBuffer.allocate(0), payload));
    }

    private CompletableFuture<@Nullable Void> enqueue(Write write) {
        lock.lock();
        try {
            if (terminal != null) {
                return CompletableFuture.failedFuture(closeWritten
                    ? new IllegalStateException("Cannot write websocket messages after close frame sent")
                    : new IOException("Cannot write websocket messages after output ended", terminal));
            }
            // Crossing close requests share the first pending frame, including a peer close
            // received while an application close is queued behind an unfinished data frame.
            if (write.opcode == 8 && closeCompletion != null && !closeCompletion.isDone()) return closeCompletion;
            queued.add(write);
            if (write.opcode == 8 && closeCompletion == null) closeCompletion = write.completion;
        } finally { lock.unlock(); }
        schedule();
        return write.completion;
    }

    @Nullable CompletableFuture<@Nullable Void> closeCompletion() {
        lock.lock();
        try { return closeCompletion; }
        finally { lock.unlock(); }
    }

    /** Reject new/queued frames, retaining any source still borrowed by the transport. */
    CompletableFuture<Void> finish() {
        terminate(new IOException("WebSocket output has ended"));
        schedule();
        return ended;
    }

    private void terminate(IOException failure) {
        lock.lock();
        try { if (terminal == null) terminal = failure; }
        finally { lock.unlock(); }
    }

    private @Nullable IOException terminal() {
        lock.lock();
        try { return terminal; }
        finally { lock.unlock(); }
    }

    private @Nullable Write next() {
        lock.lock();
        try { return queued.poll(); }
        finally { lock.unlock(); }
    }

    private boolean ready() {
        if (ended.isDone()) return false;
        lock.lock();
        try {
            if (terminal != null && !queued.isEmpty()) return true;
            CompletableFuture<?> waiting = pending;
            return waiting != null ? waiting.isDone() : terminal != null || !queued.isEmpty();
        } finally { lock.unlock(); }
    }

    private void schedule() {
        if (!ready() || !scheduled.compareAndSet(false, true)) return;
        try { executor.execute(this::run); }
        catch (RejectedExecutionException rejected) {
            IOException failure = new IOException("WebSocket writer executor rejected work", rejected);
            dispatchFailure = failure;
            terminate(failure);
            abortTransport.run();
            Thread cleanup = new Thread(this::run, "mu-websocket-rejected-writer");
            cleanup.setDaemon(true);
            cleanup.start();
        }
    }

    private void run() {
        try {
            IOException rejected = dispatchFailure;
            if (rejected != null) {
                dispatchFailure = null;
                fail(rejected);
            }
            for (int count = 0; count < STEPS_PER_TURN; count++) {
                IOException stopped = terminal();
                if (stopped != null) {
                    Write cancelled = next();
                    if (cancelled != null) {
                        cancelled.completion.completeExceptionally(stopped);
                        continue;
                    }
                }
                CompletableFuture<?> waiting = pending;
                if (waiting != null) {
                    if (!waiting.isDone()) return;
                    pending = null;
                    try { waiting.join(); }
                    catch (CompletionException failed) {
                        Throwable cause = java.util.Objects.requireNonNull(failed.getCause());
                        fail(cause instanceof IOException ? (IOException) cause : new IOException("WebSocket write failed", cause));
                        FatalErrors.rethrow(cause);
                        continue;
                    }
                    events.bytesSent(pendingBytes);
                    stopped = terminal();
                    if (stopped != null) {
                        completeActive(stopped);
                        continue;
                    }
                    Write current = java.util.Objects.requireNonNull(active);
                    if (!payloadWriting && current.payload.hasRemaining()) {
                        begin(current.payload, true);
                    } else {
                        WebsocketWriteState.Frame sent = java.util.Objects.requireNonNull(frame);
                        state.written(sent);
                        if (sent.close) {
                            closeWritten = true;
                            events.closeWritten();
                        }
                        completeActive(null);
                    }
                    continue;
                }
                if (stopped != null) {
                    completeActive(stopped);
                    ended.complete(null);
                    return;
                }
                Write current = next();
                if (current == null) return;
                active = current;
                if (current.automaticPing != null) {
                    ByteBuffer ping = current.automaticPing.get();
                    if (ping == null) { completeActive(null); continue; }
                    current.payload = ping;
                }
                try { frame = state.prepare(current.opcode, current.fin, current.fragment, current.payload.remaining()); }
                catch (IllegalArgumentException | IllegalStateException invalid) {
                    clearInvalidClose(current);
                    completeActive(invalid);
                    continue;
                }
                WebsocketWriteState.Frame prepared = java.util.Objects.requireNonNull(frame);
                if (prepared.close) events.closeStarted();
                begin(ByteBuffer.wrap(prepared.header), false);
            }
        } catch (Throwable failure) {
            fail(failure instanceof IOException ? (IOException) failure : new IOException("WebSocket writer failed", failure));
            FatalErrors.rethrow(failure);
        } finally {
            scheduled.set(false);
            if (ready()) schedule();
        }
    }

    @SuppressWarnings("ReferenceEquality") // The first close request owns this exact completion future.
    private void clearInvalidClose(Write invalid) {
        if (invalid.opcode != 8) return;
        lock.lock();
        try { if (closeCompletion == invalid.completion) closeCompletion = null; }
        finally { lock.unlock(); }
    }

    private void begin(ByteBuffer bytes, boolean payload) throws IOException {
        payloadWriting = payload;
        pendingBytes = bytes.remaining();
        pending = output.write(bytes);
        pending.whenComplete((ignored, failure) -> schedule());
    }

    private void completeActive(@Nullable Throwable failure) {
        Write finished = active;
        active = null;
        frame = null;
        if (finished != null) {
            if (failure == null) finished.completion.complete(null);
            else finished.completion.completeExceptionally(failure);
        }
    }

    private void fail(IOException failure) {
        state.fail(failure);
        terminate(failure);
        if (!failureReported) {
            failureReported = true;
            events.failed(failure);
        }
        abortTransport.run();
        // A transport future owns the payload until it acknowledges failure. A synchronous
        // submission failure did not borrow storage and can release the active command now.
        if (pending == null) completeActive(failure);
    }
}
