package io.muserver;

import org.jspecify.annotations.Nullable;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * One-owner TLS record driver. All calls belong to the transport loop. Each advance performs at
 * most one engine operation or delegated-task transition; the caller must impose a per-turn quota.
 * Delegated tasks run on a separate executor. No engine method is called while that batch runs.
 * The continuation only schedules transport work and must not block, throw, or call this driver.
 *
 * <p>Three bounded buffers own encrypted input, encrypted output, and decoded plaintext. A caller
 * must drain plaintext before another unwrap. Output may remain stalled while input progresses.
 * Application writes acknowledge plaintext only after its encrypted record has drained.</p>
 */
final class TlsEngineDriver {
    static final int DEFAULT_MAX_BUFFER_SIZE = 256 * 1024;
    private static final ByteBuffer EMPTY = ByteBuffer.allocate(0).asReadOnlyBuffer();
    private final SSLEngine engine;
    private final Executor taskExecutor;
    private final Runnable continuation;
    private final int maxBufferSize;
    private ByteBuffer encryptedInput; // write mode
    private ByteBuffer encryptedOutput; // read mode
    private ByteBuffer plaintext; // read mode
    private @Nullable CompletableFuture<Void> tasks;
    private @Nullable IOException failure;
    private SSLSession session;
    private @Nullable String applicationProtocol;
    private boolean handshakeComplete;
    private boolean inputEnded;
    private boolean inboundDone;
    private boolean outboundDone;
    private boolean closeRequested;
    private boolean closeStarted;
    private boolean needsMoreInput;
    private boolean stalledWrite;
    private boolean failureCloseFinished;
    private int unacknowledgedPlaintext;
    private long revision;

    TlsEngineDriver(SSLEngine engine, Executor taskExecutor, Runnable continuation) throws SSLException {
        this(engine, taskExecutor, continuation, DEFAULT_MAX_BUFFER_SIZE);
    }

    TlsEngineDriver(SSLEngine engine, Executor taskExecutor, Runnable continuation, int maxBufferSize) throws SSLException {
        if (maxBufferSize < 1) throw new IllegalArgumentException("Positive TLS buffer bound required");
        this.engine = engine;
        this.taskExecutor = taskExecutor;
        this.continuation = continuation;
        this.maxBufferSize = maxBufferSize;
        session = engine.getSession();
        encryptedInput = ByteBuffer.allocate(initialSize(session.getPacketBufferSize()));
        encryptedOutput = ByteBuffer.allocate(encryptedInput.capacity()).flip();
        plaintext = ByteBuffer.allocate(initialSize(session.getApplicationBufferSize())).flip();
        engine.beginHandshake();
    }

    long revision() { return revision; }
    boolean handshakeComplete() { return handshakeComplete; }
    boolean tasksPending() { return tasks != null; }
    SSLSession session() { return session; }
    @Nullable String applicationProtocol() { return applicationProtocol; }
    boolean inboundDone() { return inboundDone && !plaintext.hasRemaining(); }
    boolean outboundDone() { return outboundDone && !encryptedOutput.hasRemaining(); }
    int encryptedOutputSize() { return encryptedOutput.remaining(); }
    int plaintextSize() { return plaintext.remaining(); }
    boolean failureCloseDone() { return failureCloseFinished && !encryptedOutput.hasRemaining(); }
    int inputCapacity() { return inputEnded || inboundDone || failure != null ? 0 : encryptedInput.remaining(); }

    /** Copy encrypted input without retaining the caller's storage. Zero means input backpressure. */
    int receive(ByteBuffer source) throws IOException {
        checkFailure();
        if (inputEnded || inboundDone) throw new SSLException("TLS input is closed");
        int count = transfer(source, encryptedInput);
        received(count);
        return count;
    }

    /** One bounded read from a nonblocking channel, directly into the owned packet buffer. */
    int readFrom(ReadableByteChannel channel, int budget) throws IOException {
        checkBudget(budget);
        checkFailure();
        if (inputCapacity() == 0) return 0;
        int limit = encryptedInput.limit();
        encryptedInput.limit(encryptedInput.position() + Math.min(budget, encryptedInput.remaining()));
        try {
            int read = channel.read(encryptedInput);
            if (read == -1) endOfInput();
            else received(read);
            return read;
        } catch (IOException | RuntimeException error) {
            throw aborted(error);
        } finally { encryptedInput.limit(limit); }
    }

    /** One bounded write to a nonblocking channel. The channel must not retain the buffer. */
    int drainEncryptedTo(WritableByteChannel channel, int budget) throws IOException {
        return drainEncrypted(channel, budget, false);
    }

    /** Best-effort alert output after failure. The caller must enforce a separate close deadline. */
    int drainFailureTo(WritableByteChannel channel, int budget) throws IOException {
        if (failure == null) throw new IllegalStateException("TLS has not failed");
        return drainEncrypted(channel, budget, true);
    }

    private int drainEncrypted(WritableByteChannel channel, int budget, boolean afterFailure) throws IOException {
        checkBudget(budget);
        if (!afterFailure) checkFailure();
        if (!encryptedOutput.hasRemaining()) return 0;
        int limit = encryptedOutput.limit();
        encryptedOutput.limit(encryptedOutput.position() + Math.min(budget, encryptedOutput.remaining()));
        boolean restoreLimit = true;
        try {
            int written = channel.write(encryptedOutput);
            if (written > 0) { revision++; stalledWrite = false; }
            return written;
        } catch (IOException | RuntimeException error) {
            restoreLimit = false;
            throw aborted(error);
        } finally { if (restoreLimit) encryptedOutput.limit(limit); }
    }

    int readPlaintext(ByteBuffer target) throws IOException {
        checkFailure();
        int count = transfer(plaintext, target);
        if (count > 0) revision++;
        return count;
    }

    /**
     * The caller retains its unconsumed prefix until a positive acknowledgement is returned. Wrapping
     * uses a duplicate: even consumed plaintext does not advance source until ciphertext drains.
     * No reference to source or its storage is retained between calls. Suitable as the sink behind
     * TransportOutputBuffer; the same queued prefix must be supplied on each retry.
     */
    int writePlaintext(ByteBuffer source) throws IOException {
        checkFailure();
        try {
            if (unacknowledgedPlaintext > 0) {
                if (encryptedOutput.hasRemaining()) return 0;
                int count = Math.min(source.remaining(), unacknowledgedPlaintext);
                source.position(source.position() + count);
                unacknowledgedPlaintext -= count;
                if (count > 0) revision++;
                return count;
            }
            if (closeRequested || outboundDone) throw new SSLException("TLS output is closed");
            if (!handshakeComplete || tasks != null || stalledWrite || encryptedOutput.hasRemaining()
                || engine.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
                || !source.hasRemaining()) return 0;
            wrap(source.duplicate(), true);
            return 0;
        } catch (IOException | RuntimeException error) { throw failed(error); }
    }

    /** Request an orderly outbound close after any already-wrapped plaintext is acknowledged. */
    void closeOutbound() { closeRequested = true; revision++; }

    /** Record TCP EOF; buffered records and plaintext are processed before checking close-notify. */
    void endOfInput() { inputEnded = true; revision++; }

    /** No engine access: outstanding delegated tasks cannot delay transport abort. */
    void abort(IOException cause) { aborted(cause); }

    /**
     * Optional failure-close progression. Application operations remain failed. Drain an already
     * partially sent record before generating an alert, preserving record order. A pending task
     * never blocks this method; a deadline or hard abort may discard the connection at any point.
     */
    boolean advanceFailureClose() {
        if (failure == null) throw new IllegalStateException("TLS has not failed");
        if (failureCloseFinished || encryptedOutput.hasRemaining()) return false;
        if (tasks != null) {
            if (!tasks.isDone()) return false;
            try { tasks.join(); } catch (CompletionException ignored) { }
            tasks = null;
        }
        try {
            if (!closeStarted) {
                engine.closeOutbound();
                closeStarted = true;
                revision++;
                return true;
            }
            if (engine.isOutboundDone()) {
                failureCloseFinished = true;
                return false;
            }
            encryptedOutput.clear();
            SSLEngineResult result;
            try { result = engine.wrap(EMPTY.duplicate(), encryptedOutput); }
            finally { encryptedOutput.flip(); }
            if (result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                encryptedOutput = growReadable(encryptedOutput, engine.getSession().getPacketBufferSize());
                revision++;
                return true;
            }
            failureCloseFinished = result.getStatus() == SSLEngineResult.Status.CLOSED || engine.isOutboundDone()
                || result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.NEED_TASK;
            if (result.bytesProduced() > 0) { revision++; return true; }
            return false;
        } catch (SSLException | RuntimeException closeFailure) {
            // Preserve the original failure. Any alert bytes already produced can still be drained.
            failureCloseFinished = true;
            return encryptedOutput.hasRemaining();
        }
    }

    boolean advance() throws IOException {
        checkFailure();
        try {
            if (tasks != null) {
                if (!tasks.isDone()) return false;
                tasks.join();
                tasks = null;
                needsMoreInput = false;
                stalledWrite = false;
                revision++;
                return true;
            }
            if (closeRequested && !closeStarted && unacknowledgedPlaintext == 0) {
                engine.closeOutbound();
                closeStarted = true;
                refreshSession();
                revision++;
                return true;
            }
            switch (engine.getHandshakeStatus()) {
                case NEED_TASK:
                    runDelegatedTasks();
                    return true;
                case NEED_WRAP:
                    return !encryptedOutput.hasRemaining() && wrap(EMPTY.duplicate(), false);
                case NEED_UNWRAP_AGAIN:
                    return !plaintext.hasRemaining() && unwrap(true);
                case NEED_UNWRAP:
                case NOT_HANDSHAKING:
                    if (plaintext.hasRemaining() || inboundDone) return false;
                    if (encryptedInput.position() > 0 && !needsMoreInput) return unwrap(false);
                    if (inputEnded) {
                        // Throws on missing close-notify, including a truncated buffered record.
                        engine.closeInbound();
                        refreshSession();
                        if (!inboundDone) throw new SSLException("TLS provider did not close its input");
                        revision++;
                        return true;
                    }
                    return false;
                default:
                    throw new SSLException("Unexpected engine handshake status: " + engine.getHandshakeStatus());
            }
        } catch (CompletionException taskFailure) {
            throw failed(taskFailure.getCause() == null ? taskFailure : taskFailure.getCause());
        } catch (IOException | RuntimeException error) { throw failed(error); }
    }

    private boolean wrap(ByteBuffer source, boolean application) throws SSLException {
        SSLEngineResult.HandshakeStatus before = engine.getHandshakeStatus();
        encryptedOutput.clear();
        SSLEngineResult result;
        try { result = engine.wrap(source, encryptedOutput); }
        finally { encryptedOutput.flip(); }
        boolean progress = recordResult(result, before);
        if (application && result.bytesConsumed() > 0) {
            if (result.bytesProduced() == 0) throw new SSLException("TLS consumed plaintext without producing a record");
            unacknowledgedPlaintext = result.bytesConsumed();
        }
        switch (result.getStatus()) {
            case BUFFER_OVERFLOW:
                encryptedOutput = growReadable(encryptedOutput, session.getPacketBufferSize());
                revision++;
                return true;
            case BUFFER_UNDERFLOW:
                throw new SSLException("TLS wrap returned buffer underflow");
            default:
                if (!progress && application) stalledWrite = true;
                return progress;
        }
    }

    private boolean unwrap(boolean again) throws SSLException {
        SSLEngineResult.HandshakeStatus before = engine.getHandshakeStatus();
        encryptedInput.flip();
        plaintext.clear();
        SSLEngineResult result;
        try { result = engine.unwrap(again ? EMPTY.duplicate() : encryptedInput, plaintext); }
        finally { encryptedInput.compact(); plaintext.flip(); }
        boolean progress = recordResult(result, before);
        if (result.bytesProduced() > 0 && !handshakeComplete) {
            throw new SSLException("TLS produced application data before authenticating the handshake");
        }
        switch (result.getStatus()) {
            case BUFFER_OVERFLOW:
                plaintext = growReadable(plaintext, session.getApplicationBufferSize());
                revision++;
                return true;
            case BUFFER_UNDERFLOW:
                needsMoreInput = true;
                if (session.getPacketBufferSize() > encryptedInput.capacity() || !encryptedInput.hasRemaining()) {
                    ByteBuffer grown = ByteBuffer.allocate(grownSize(encryptedInput.capacity(), session.getPacketBufferSize()));
                    encryptedInput.flip();
                    grown.put(encryptedInput);
                    encryptedInput = grown;
                    revision++;
                    return true;
                }
                return progress || inputEnded;
            default:
                if (!progress && !again) needsMoreInput = true;
                return progress;
        }
    }

    private boolean recordResult(SSLEngineResult result, SSLEngineResult.HandshakeStatus before) {
        boolean progress = result.bytesConsumed() != 0 || result.bytesProduced() != 0
            || result.getHandshakeStatus() != before;
        if (result.getHandshakeStatus() != before) needsMoreInput = false;
        if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) handshakeComplete = true;
        boolean wasInboundDone = inboundDone;
        boolean wasOutboundDone = outboundDone;
        refreshSession();
        progress |= wasInboundDone != inboundDone || wasOutboundDone != outboundDone;
        if (progress) { revision++; stalledWrite = false; }
        return progress;
    }

    private void refreshSession() {
        session = engine.getSession();
        inboundDone = engine.isInboundDone();
        outboundDone = engine.isOutboundDone();
        if (handshakeComplete) {
            try { applicationProtocol = engine.getApplicationProtocol(); }
            catch (UnsupportedOperationException unsupported) { applicationProtocol = null; }
        }
    }

    private void runDelegatedTasks() throws SSLException {
        List<Runnable> batch = new ArrayList<>();
        // Bound retained task objects even for providers exposing unusually large batches.
        for (int i = 0; i < 64; i++) {
            Runnable task = engine.getDelegatedTask();
            if (task == null) break;
            batch.add(task);
        }
        if (batch.isEmpty()) throw new SSLException("TLS requested a task but supplied none");
        CompletableFuture<Void> completion = new CompletableFuture<>();
        tasks = completion;
        try {
            taskExecutor.execute(() -> {
                try {
                    for (Runnable task : batch) task.run();
                    completion.complete(null);
                } catch (Throwable error) {
                    completion.completeExceptionally(error);
                    FatalErrors.rethrow(error);
                } finally { continuation.run(); }
            });
        } catch (RejectedExecutionException rejected) {
            // No task owns the engine. Failure-close must not wait on an unsubmitted batch.
            completion.completeExceptionally(rejected);
            throw rejected;
        }
        revision++;
    }

    private void received(int count) {
        if (count > 0) { needsMoreInput = false; stalledWrite = false; revision++; }
    }

    private int initialSize(int requested) throws SSLException {
        if (requested < 1 || requested > maxBufferSize) throw new SSLException("TLS buffer requirement exceeds limit: " + requested);
        return requested;
    }

    private int grownSize(int current, int requested) throws SSLException {
        if (requested < 1 || requested > maxBufferSize || current >= maxBufferSize) throw new SSLException("TLS buffer limit exceeded");
        return (int) Math.min(maxBufferSize, Math.max((long) requested, (long) current * 2));
    }

    private ByteBuffer growReadable(ByteBuffer original, int requested) throws SSLException {
        ByteBuffer grown = ByteBuffer.allocate(grownSize(original.capacity(), requested));
        return grown.put(original).flip();
    }

    private static int transfer(ByteBuffer source, ByteBuffer target) {
        int count = Math.min(source.remaining(), target.remaining());
        int limit = source.limit();
        source.limit(source.position() + count);
        try { target.put(source); }
        finally { source.limit(limit); }
        return count;
    }

    private static void checkBudget(int budget) {
        if (budget < 1) throw new IllegalArgumentException("Positive TLS IO budget required");
    }

    private IOException failed(Throwable cause) {
        if (failure == null) failure = cause instanceof IOException ? (IOException) cause : new SSLException("TLS progression failed", cause);
        encryptedInput.clear();
        plaintext.clear().flip();
        unacknowledgedPlaintext = 0;
        revision++;
        return failure;
    }

    private IOException aborted(Throwable cause) {
        IOException reason = failed(cause);
        encryptedOutput.clear().flip();
        failureCloseFinished = true;
        return reason;
    }

    private void checkFailure() throws IOException {
        if (failure != null) throw failure;
    }
}
