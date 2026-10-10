package io.muserver;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class WebsocketConnection implements MuWebSocketSession {
    private static final Logger log = LoggerFactory.getLogger(WebsocketConnection.class);
    private @Nullable ByteBuffer buffer;
    private @Nullable InputStream inputStream;
    private @Nullable OutputStream outputStream;
    final WebSocketHandlerBuilder.Settings settings;

    private final WebsocketLifecycle lifecycle = new WebsocketLifecycle();
    private final Http1Connection httpConnection;
    private final Mu3ServerImpl server;
    private final SerialApplicationTasks writeCallbacks;
    private final MuWebSocket webSocket;
    // The head remains queued while running or awaiting asynchronous receive completion.
    private final Queue<ApplicationEventTask> applicationEvents = new ConcurrentLinkedQueue<>();
    private volatile @Nullable RejectedExecutionException applicationEventRejection;
    private final AtomicBoolean applicationEventRunnerScheduled = new AtomicBoolean();
    private final AtomicBoolean errorEventQueued = new AtomicBoolean();
    private final AtomicBoolean serverShutdownRequested = new AtomicBoolean();
    private volatile boolean closeReceived = false;
    private volatile boolean closeSent = false;
    private final Lock writeLock = new ReentrantLock();
    private final @Nullable WebsocketPingTracker pingTracker;
    private volatile @Nullable ScheduledFuture<?> pingFuture;

    @FunctionalInterface
    private interface ApplicationEvent {
        void run() throws Exception;
    }

    private static final class ApplicationEventTask {
        private final ApplicationEvent event;
        // 0 queued, 1 invoking application code, 2 deferred acknowledgement, 3 completed.
        private final AtomicInteger phase = new AtomicInteger();
        private final CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();

        private ApplicationEventTask(ApplicationEvent event) {
            this.event = event;
        }
    }

    WebsocketConnection(Http1Connection httpConnection, MuWebSocket webSocket, WebSocketHandlerBuilder.Settings settings) {
        this.httpConnection = httpConnection;
        this.server = httpConnection.serverImpl();
        this.writeCallbacks = new SerialApplicationTasks(server);
        this.webSocket = webSocket;
        this.settings = settings;
        if (settings.pingIntervalMillis == 0) {
            pingTracker = null;
        } else {
            pingTracker = new WebsocketPingTracker();
        }

    }

    MuWebSocket webSocket() {
        return webSocket;
    }

    void dispatchWriteCallback(DoneCallback callback, @Nullable Throwable failure) {
        writeCallbacks.submit(() -> {
            try { callback.onComplete(failure); }
            catch (Exception callbackFailure) { log.warn("WebSocket write callback failed", callbackFailure); }
        }, this::failApplicationEvents);
    }

    ExecutorService asyncExecutor() {
        return server.internalExecutor();
    }

    private void startPinging() {
        pingFuture = httpConnection.serverImpl().scheduleConnectionTask(() -> {
            writeLock.lock();
            try {
                if (lifecycle.state() == WebsocketSessionState.OPEN) {
                    sendPing(java.util.Objects.requireNonNull(pingTracker).newPingPayload());
                }
                if (lifecycle.state() == WebsocketSessionState.OPEN) {
                    startPinging();
                }
            } catch (IOException e) {
                if (lifecycle.state() == WebsocketSessionState.OPEN) {
                    // force an IO exception on the read operation in runAndBlockUntilDone()
                    Mutils.closeSilently(inputStream);
                }
            } finally {
                writeLock.unlock();
            }

        }, settings.pingIntervalMillis, TimeUnit.MILLISECONDS);
    }

    public void runAndBlockUntilDone(InputStream inputStream, OutputStream outputStream, ByteBuffer readBuffer) throws InterruptedException, ApplicationEventFailure {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.buffer = readBuffer;

        try {
            lifecycle.onConnected();
            invokeApplicationEvent(() -> webSocket.onConnect(this));

            if (settings.pingIntervalMillis > 0) {
                startPinging();
            }

            var decoder = new WebsocketFrameDecoder(settings.maxFramePayloadLength, settings.maxMessageLength);
            while (!closeReceived) {
                WebsocketFrameDecoder.Frame frame;
                try {
                    frame = decoder.decode(readBuffer);
                } catch (WebsocketFrameDecoder.InvalidFrame invalid) {
                    close(invalid.closeCode, invalid.getMessage());
                    throw invalid;
                }
                if (frame == null) {
                    readAtLeast(1);
                    continue;
                }
                invokeApplicationEvent(frameEvent(frame));
                if (closeReceived) completeCloseHandshakeIfCloseSent();
            }

            // it's finished - the TCP connection will be closed
        } catch (Throwable failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw (InterruptedException) failure;
            }
            ApplicationEvent error = prepareError(failure);
            if (error != null) invokeApplicationEvent(error);
        } finally {
            stopPinging();
        }
    }

    private void stopPinging() {
        ScheduledFuture<?> currentPing = pingFuture;
        if (currentPing != null) {
            currentPing.cancel(false);
            pingFuture = null;
        }
    }

    private @Nullable ApplicationEvent prepareError(Throwable failure) {
        boolean applicationFailure = failure instanceof ApplicationEventFailure;
        Throwable cause = applicationFailure ? Objects.requireNonNull(failure.getCause()) : failure;
        FatalErrors.rethrow(cause);
        if ((!serverShutdownRequested.get() || applicationFailure)
            && !errorEventQueued.get()
            && lifecycle.state() != WebsocketSessionState.TIMED_OUT) {
            WebsocketSessionState errorState = cause instanceof TimeoutException || cause instanceof SocketTimeoutException
                ? WebsocketSessionState.TIMED_OUT : WebsocketSessionState.ERRORED;
            if (errorState == WebsocketSessionState.TIMED_OUT) {
                // Publish termination before onError so a default callback cannot wait to send
                // a close frame behind a pending write. Aborting also releases that write.
                lifecycle.terminateWith(errorState);
                httpConnection.forceShutdown();
            }
            if (errorEventQueued.compareAndSet(false, true)) return errorEvent(cause, errorState);
        }
        return null;
    }

    ReadDriver readDriver(TransportInputBuffer input, OutputStream output, ByteBuffer prefetched) {
        inputStream = input;
        outputStream = output;
        return new ReadDriver(input, prefetched);
    }

    /** Readiness callbacks only schedule work; decoding and application dispatch stay off the selector. */
    final class ReadDriver {
        private static final int STEPS_PER_TURN = 64;
        private final TransportInputBuffer input;
        private final ByteBuffer bytes;
        private final WebsocketFrameDecoder decoder =
            new WebsocketFrameDecoder(settings.maxFramePayloadLength, settings.maxMessageLength);
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final CompletableFuture<Void> ended = new CompletableFuture<>();
        private volatile @Nullable CompletableFuture<@Nullable Void> pending;
        private volatile @Nullable ReadDeadline deadline;
        private volatile @Nullable Throwable submissionFailure;
        private volatile boolean needsInput;
        private boolean connected;
        private boolean connectCompleted;
        private boolean finishing;

        private ReadDriver(TransportInputBuffer input, ByteBuffer prefetched) {
            this.input = input;
            this.bytes = prefetched;
        }

        CompletableFuture<Void> completion() { return ended; }

        void inputAvailable() {
            if (ready()) schedule();
        }

        void ownerFailed(IOException failure) {
            input.fail(failure);
            inputAvailable();
        }

        private boolean ready() {
            if (ended.isDone()) return false;
            CompletableFuture<?> work = pending;
            if (work != null) return work.isDone();
            ReadDeadline waiting = deadline;
            return submissionFailure != null || !needsInput || input.readable()
                || (waiting != null && waiting.expired);
        }

        private void schedule() {
            if (ended.isDone() || !scheduled.compareAndSet(false, true)) return;
            try { server.executeInternalTask(this::run); }
            catch (RejectedExecutionException rejected) {
                submissionFailure = rejected;
                // Normally prevented by the transport's resource lease. Exceptional cleanup
                // must still avoid invoking application callbacks on the notifying selector.
                Thread cleanup = new Thread(this::run, "mu-websocket-rejected-reader");
                cleanup.setDaemon(true);
                cleanup.start();
            }
        }

        private void run() {
            needsInput = false;
            try {
                if (!connected) {
                    connected = true;
                    if (submissionFailure != null) throw submissionFailure;
                    lifecycle.onConnected();
                    await(enqueueApplicationEvent(() -> webSocket.onConnect(WebsocketConnection.this)));
                }
                for (int step = 0; step < STEPS_PER_TURN && !ended.isDone(); step++) {
                    CompletableFuture<?> work = pending;
                    if (work != null) {
                        if (!work.isDone()) return;
                        pending = null;
                        try { work.join(); }
                        catch (java.util.concurrent.CompletionException failure) {
                            throw new ApplicationEventFailure(Objects.requireNonNull(failure.getCause()));
                        }
                        if (finishing) { finish(null); return; }
                    }
                    if (submissionFailure != null) throw submissionFailure;
                    if (!connectCompleted) {
                        connectCompleted = true;
                        if (settings.pingIntervalMillis > 0) startPinging();
                    }
                    if (closeReceived) {
                        completeCloseHandshakeIfCloseSent();
                        endAfterEvents(null);
                        return;
                    }
                    ReadDeadline waiting = deadline;
                    if (waiting != null && waiting.expired) throw new SocketTimeoutException("WebSocket read timed out");
                    // Failure wins over any prefetched frame after an outstanding callback returns.
                    try { input.available(); }
                    catch (java.io.InterruptedIOException failure) { throw failure; }
                    catch (IOException failure) { throw disconnected(failure); }
                    WebsocketFrameDecoder.Frame frame;
                    try { frame = decoder.decode(bytes); }
                    catch (WebsocketFrameDecoder.InvalidFrame invalid) {
                        // The output migration replaces this remaining protocol-error write wait.
                        close(invalid.closeCode, invalid.getMessage());
                        throw invalid;
                    }
                    if (frame != null) {
                        cancelReadDeadline();
                        await(enqueueApplicationEvent(frameEvent(frame)));
                        continue;
                    }
                    bytes.clear();
                    int count;
                    try { count = input.readAvailable(bytes.array(), bytes.arrayOffset(), bytes.capacity()); }
                    catch (java.io.InterruptedIOException failure) { throw failure; }
                    catch (IOException failure) { throw disconnected(failure); }
                    finally { bytes.flip(); }
                    if (count == -1) decoder.endOfInput();
                    if (count == 0) {
                        needsInput = true;
                        armReadDeadline();
                        return;
                    }
                    bytes.limit(count);
                    cancelReadDeadline();
                }
            } catch (Throwable failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                if (finishing) finish(failure);
                else {
                    try { endAfterEvents(prepareError(failure)); }
                    catch (Throwable errorFailure) { finish(errorFailure); FatalErrors.rethrow(errorFailure); }
                }
                FatalErrors.rethrow(failure);
            } finally {
                scheduled.set(false);
                // An offer or event completion racing suspension cannot be lost.
                if (ready()) schedule();
            }
        }

        private void await(CompletableFuture<@Nullable Void> work) {
            pending = work;
            work.whenComplete((ignored, failure) -> schedule());
        }

        private void endAfterEvents(@Nullable ApplicationEvent error) {
            finishing = true;
            cancelReadDeadline();
            stopPinging();
            // A barrier also retains an error/shutdown callback already queued by another task.
            await(enqueueApplicationEvent(error == null ? () -> { } : error));
        }

        private void finish(@Nullable Throwable failure) {
            cancelReadDeadline();
            stopPinging();
            pending = null;
            if (failure == null) ended.complete(null);
            else ended.completeExceptionally(failure);
        }

        @SuppressWarnings("ReferenceEquality") // Each timer owns exactly one read-wait generation.
        private void armReadDeadline() {
            if (deadline != null || settings.idleReadTimeoutMillis == 0) return;
            ReadDeadline waiting = new ReadDeadline();
            deadline = waiting;
            waiting.timer = server.scheduleTimerCallback(() -> {
                if (deadline == waiting) {
                    waiting.expired = true;
                    schedule();
                }
            }, settings.idleReadTimeoutMillis, TimeUnit.MILLISECONDS);
        }

        private void cancelReadDeadline() {
            ReadDeadline waiting = deadline;
            deadline = null;
            if (waiting != null && waiting.timer != null) waiting.timer.cancel(false);
        }
    }

    private static final class ReadDeadline {
        private volatile boolean expired;
        private @Nullable ScheduledFuture<?> timer;
    }

    private static ClientDisconnectedException disconnected(IOException failure) {
        ClientDisconnectedException disconnected = new ClientDisconnectedException();
        disconnected.initCause(failure);
        return disconnected;
    }

    /** Called by the serial reader after the complete frame has been validated. */
    private ApplicationEvent frameEvent(WebsocketFrameDecoder.Frame frame) {
        switch (frame.opcode) {
            case 0:
                return frame.text
                    ? () -> webSocket.onTextFragment(frame.payload, frame.fin)
                    : () -> webSocket.onBinaryFragment(frame.payload, frame.fin);
            case 1:
                return frame.fin
                    ? () -> webSocket.onText(StandardCharsets.UTF_8.decode(frame.payload).toString())
                    : () -> webSocket.onTextFragment(frame.payload, false);
            case 2:
                return frame.fin
                    ? () -> webSocket.onBinary(frame.payload)
                    : () -> webSocket.onBinaryFragment(frame.payload, false);
            case 8:
                lifecycle.onClientCloseStarted();
                closeReceived = true;
                return () -> webSocket.onClientClosed(frame.closeCode, frame.closeReason);
            case 9:
                return () -> webSocket.onPing(frame.payload);
            case 10:
                return () -> webSocket.onPong(frame.payload);
            default:
                throw new IllegalStateException("Unexpected decoded opcode: " + frame.opcode);
        }
    }

    private void readAtLeast(int minBytes) throws IOException {
        ByteBuffer readBuffer = java.util.Objects.requireNonNull(buffer);
        InputStream input = java.util.Objects.requireNonNull(inputStream);
        readAtLeast(readBuffer, input, minBytes);
    }

    static void readAtLeast(ByteBuffer readBuffer, InputStream input, int minBytes) throws IOException {
        if (minBytes > readBuffer.capacity()) throw new IllegalArgumentException("This buffer is not big enough");
        while (readBuffer.remaining() < minBytes) {
            // Only make room for bytes still missing. Comparing with the total minimum
            // repeatedly compacts a growing, already-buffered text prefix on short reads.
            int missing = minBytes - readBuffer.remaining();
            if (readBuffer.capacity() - readBuffer.limit() < missing) {
                readBuffer.compact().flip();
            }
            int read;
            try {
                read = input.read(readBuffer.array(), readBuffer.arrayOffset() + readBuffer.limit(),
                    readBuffer.capacity() - readBuffer.limit());
            } catch (java.io.InterruptedIOException timeoutOrInterrupt) {
                throw timeoutOrInterrupt;
            } catch (IOException transportFailure) {
                // No WebSocket close frame arrived. Preserve TLS/socket diagnostics as the
                // cause, but do not ask default error handlers to write to a failed transport.
                ClientDisconnectedException disconnected = new ClientDisconnectedException();
                disconnected.initCause(transportFailure);
                throw disconnected;
            }
            if (read == -1) {
                throw new ClientDisconnectedException();
            }
            readBuffer.limit(readBuffer.limit() + read);
        }
    }

    private void completeCloseHandshakeIfCloseSent() {
        // Synchronize with the close-frame writer before publishing that both
        // sides of the closing handshake have completed.
        writeLock.lock();
        try {
            if (closeSent) {
                lifecycle.onCloseHandshakeCompleted();
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean closeReceived() {
        return closeReceived;
    }

    @Override
    public boolean closeSent() {
        return closeSent;
    }


    private MessageWritingState messageWritingState = MessageWritingState.NONE;
    private @Nullable IOException writeFailure;

    void onTimeout() {
        if (errorEventQueued.compareAndSet(false, true)) {
            // The connection-level timeout closes the transport immediately after this
            // method returns. Publish the terminal state before the callback so default
            // implementations do not try to write a close frame to that closed transport.
            lifecycle.terminateWith(WebsocketSessionState.TIMED_OUT);
            enqueueApplicationEvent(() ->
                webSocket.onError(new TimeoutException("Connection idle timeout"))
            );
        }
    }

    void onServerShuttingDown() throws Exception {
        if (!serverShutdownRequested.compareAndSet(false, true)) {
            return;
        }
        enqueueApplicationEvent(webSocket::onServerShuttingDown)
            .whenComplete((ignored, failure) -> {
                if (failure != null) {
                    httpConnection.forceShutdown();
                }
            });
    }

    private void invokeApplicationEvent(ApplicationEvent event) throws InterruptedException, ApplicationEventFailure {
        CompletableFuture<@Nullable Void> completion = enqueueApplicationEvent(event);
        try {
            completion.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            throw new ApplicationEventFailure(Objects.requireNonNull(e.getCause()));
        }
    }

    /** Keeps application failures distinct from transport errors induced by shutdown. */
    private static final class ApplicationEventFailure extends Exception {
        private ApplicationEventFailure(Throwable cause) {
            super(cause);
        }
    }

    private @Nullable CompletableFuture<@Nullable Void> callApplicationEvent(ApplicationEvent event) throws Exception {
        return WebSocketEventCompletion.invoke(event::run);
    }

    private void enqueueApplicationError(Throwable cause, WebsocketSessionState errorState) {
        if (errorEventQueued.compareAndSet(false, true)) {
            enqueueApplicationEvent(errorEvent(cause, errorState));
        }
    }

    private ApplicationEvent errorEvent(Throwable cause, WebsocketSessionState errorState) {
        return () -> {
            try {
                webSocket.onError(cause);
            } finally {
                lifecycle.terminateWith(errorState);
            }
        };
    }

    @SuppressWarnings("ReferenceEquality") // Connection-task ownership belongs to the exact thread instance.
    private CompletableFuture<@Nullable Void> enqueueApplicationEvent(ApplicationEvent event) {
        var task = new ApplicationEventTask(event);
        RejectedExecutionException rejection = applicationEventRejection;
        if (rejection != null) {
            task.completion.completeExceptionally(rejection);
            return task.completion;
        }
        applicationEvents.add(task);
        scheduleApplicationEventRunner();
        return task.completion;
    }

    private void scheduleApplicationEventRunner() {
        RejectedExecutionException rejection = applicationEventRejection;
        if (rejection != null) {
            failApplicationEvents(rejection);
            return;
        }
        if (!applicationEventRunnerScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            RejectedExecutionException rejected = server.tryExecuteHandlerTask(this::runApplicationEvents);
            if (rejected != null) failApplicationEvents(rejected);
        } catch (RuntimeException | Error failure) {
            failApplicationEvents(new RejectedExecutionException("WebSocket event dispatch failed", failure));
            FatalErrors.rethrow(failure);
        }
    }

    private void failApplicationEvents(RejectedExecutionException failure) {
        // Rejection is terminal: closing the socket alone cannot wake a reader awaiting
        // a receive-completion callback that the application executor will never deliver.
        // Publish before draining so racing submissions also fail instead of being stranded.
        applicationEventRejection = failure;
        lifecycle.terminateWith(WebsocketSessionState.ERRORED);
        httpConnection.forceShutdown();
        ApplicationEventTask task;
        while ((task = applicationEvents.poll()) != null) {
            rejectApplicationEvent(task, failure);
        }
    }

    private void rejectApplicationEvent(ApplicationEventTask task, RejectedExecutionException failure) {
        for (;;) {
            int phase = task.phase.get();
            // Keep accounting while the application method actually runs. Once it returns,
            // rejection cancels a deferred wait; owned frame storage is never reused afterward.
            if (phase == 1 || phase == 3) return;
            if (task.phase.compareAndSet(phase, 3)) {
                applicationEvents.remove(task);
                task.completion.completeExceptionally(failure);
                return;
            }
        }
    }

    private void completeApplicationEvent(ApplicationEventTask task, @Nullable Throwable failure) {
        if (task.phase.getAndSet(3) == 3) return;
        applicationEvents.remove(task);
        if (failure == null) task.completion.complete(null);
        else task.completion.completeExceptionally(failure);
    }

    private void runApplicationEvents() {
        for (;;) {
            if (applicationEventRejection != null) return;
            ApplicationEventTask task = applicationEvents.peek();
            if (task == null) {
                applicationEventRunnerScheduled.set(false);
                if (applicationEvents.isEmpty()
                    || !applicationEventRunnerScheduled.compareAndSet(false, true)) return;
                continue;
            }
            if (!task.phase.compareAndSet(0, 1)) continue;
            RejectedExecutionException rejected = applicationEventRejection;
            if (rejected != null) {
                completeApplicationEvent(task, rejected);
                return;
            }
            try {
                CompletableFuture<@Nullable Void> deferred = callApplicationEvent(task.event);
                if (deferred != null && !deferred.isDone()) {
                    task.phase.set(2);
                    // Rejection during invocation must wait for the method to return, but an
                    // acknowledgement chained to a rejected write callback may never arrive.
                    rejected = applicationEventRejection;
                    if (rejected != null) rejectApplicationEvent(task, rejected);
                    deferred.whenComplete((ignored, failure) -> {
                        completeApplicationEvent(task, failure);
                        applicationEventRunnerScheduled.set(false);
                        if (!applicationEvents.isEmpty()) scheduleApplicationEventRunner();
                    });
                    return;
                }
                if (deferred != null) deferred.join();
                completeApplicationEvent(task, null);
            } catch (Throwable failure) {
                completeApplicationEvent(task, failure);
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                FatalErrors.rethrow(failure);
            }
        }
    }

    private enum MessageWritingState {
        NONE, TEXT, BINARY, ERROR
    }

    @Override
    public void sendText(String message) throws IOException {
        var payload = message.getBytes(StandardCharsets.UTF_8);
        writeFragment((byte)0b10000001, payload, 0, payload.length, MessageWritingState.NONE, MessageWritingState.NONE);
    }

    @Override
    public void sendTextFragment(ByteBuffer fragment, boolean isLastFragment) throws IOException {
        writeLock.lock();
        try {
            throwStoredWriteFailure();
            var payload = arrayBuffer(fragment);
            int off = payload.arrayOffset() + payload.position();
            int len = payload.remaining();
            if (isLastFragment && messageWritingState == MessageWritingState.NONE) {
                // this is just a non-fragmented full message, so use the plain send
                writeFragment((byte) 0b10000001, payload.array(), off, len, MessageWritingState.NONE, MessageWritingState.NONE);
            } else {
                if (!isLastFragment && messageWritingState == MessageWritingState.NONE) {
                    // the first message of a fragmented text message
                    writeFragment((byte) 0b00000001, payload.array(), off, len, MessageWritingState.NONE, MessageWritingState.TEXT);
                } else if (!isLastFragment && messageWritingState == MessageWritingState.TEXT) {
                    // a middle fragment of a text message
                    writeFragment((byte) 0b00000000, payload.array(), off, len, MessageWritingState.TEXT, MessageWritingState.TEXT);
                } else if (isLastFragment && messageWritingState == MessageWritingState.TEXT) {
                    // the last fragment of a text message
                    writeFragment((byte) 0b10000000, payload.array(), off, len, MessageWritingState.TEXT, MessageWritingState.NONE);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void sendBinary(ByteBuffer message) throws IOException {
        var payload = arrayBuffer(message);
        writeFragment((byte)0b10000010, payload.array(), payload.arrayOffset() + payload.position(), payload.remaining(), MessageWritingState.NONE, MessageWritingState.NONE);
    }

    @Override
    public void sendBinaryFragment(ByteBuffer message, boolean isLastFragment) throws IOException {
        writeLock.lock();
        try {
            throwStoredWriteFailure();
            if (isLastFragment && messageWritingState == MessageWritingState.NONE) {
                // this is just a non-fragmented full message, so use the plain send
                sendBinary(message);
            } else {
                var payload = arrayBuffer(message);
                int off = payload.arrayOffset() + payload.position();
                int len = payload.remaining();
                if (!isLastFragment && messageWritingState == MessageWritingState.NONE) {
                    // the first message of a fragmented binary message
                    writeFragment((byte) 0b00000010, payload.array(), off, len, MessageWritingState.NONE, MessageWritingState.BINARY);
                } else if (!isLastFragment && messageWritingState == MessageWritingState.BINARY) {
                    // a middle fragment of a binary message
                    writeFragment((byte) 0b00000000, payload.array(), off, len, MessageWritingState.BINARY, MessageWritingState.BINARY);
                } else if (isLastFragment && messageWritingState == MessageWritingState.BINARY) {
                    // the last fragment of a binary message
                    writeFragment((byte) 0b10000000, payload.array(), off, len, MessageWritingState.BINARY, MessageWritingState.NONE);
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void sendPing(ByteBuffer payload) throws IOException {
        requireControlPayloadSize(payload.remaining());
        payload = arrayBuffer(payload);
        writeFragment((byte)0b10001001, payload.array(), payload.arrayOffset() + payload.position(), payload.remaining(), null, null);
    }

    @Override
    public void sendPong(ByteBuffer payload) throws IOException {
        requireControlPayloadSize(payload.remaining());
        payload = arrayBuffer(payload);
        writeFragment((byte)0b10001010, payload.array(), payload.arrayOffset() + payload.position(), payload.remaining(), null, null);
    }

    @Override
    public void close() throws IOException {
        writeLock.lock();
        try {
            lifecycle.onServerCloseStarted();
            writeFragment((byte)0b10001000, null, 0, 0, null, null);
            if (!closeSent) {
                closeSent = true;
            }
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void close(int statusCode, @Nullable String reason) throws IOException {
        if (statusCode < 1000 || statusCode > 4999) {
            throw new IllegalArgumentException("Websocket closure codes must be between 1000 and 4999 (inclusive)");
        }
        byte[] reasonBytes = reason == null || reason.isEmpty()
            ? new byte[0]
            : reason.getBytes(StandardCharsets.UTF_8);
        requireControlPayloadSize(reasonBytes.length + 2);
        var payload = new byte[reasonBytes.length + 2];
        payload[0] = (byte) ((statusCode >> 8) & 0xFF);
        payload[1] = (byte) (statusCode & 0xFF);
        System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);

        writeLock.lock();
        try {
            lifecycle.onServerCloseStarted();
            writeFragment((byte)0b10001000, payload, 0, payload.length, null, null);
            if (!closeSent) {
                closeSent = true;
            }
        } finally {
            writeLock.unlock();
        }
    }

    private static void requireControlPayloadSize(int payloadLength) {
        if (payloadLength > 125) {
            throw new IllegalArgumentException(
                "WebSocket control frame payload cannot exceed 125 bytes"
            );
        }
    }

    private static ByteBuffer arrayBuffer(ByteBuffer source) {
        if (source.hasArray()) {
            return source;
        }
        var arr = new byte[source.remaining()];
        source.get(arr);
        return ByteBuffer.wrap(arr);
    }

    private void writeFragment(byte firstByte, byte@Nullable[] payload, int payloadOffset, int payloadLen, @Nullable MessageWritingState expectedState, @Nullable MessageWritingState endState) throws IOException {
        if ((firstByte & 0x08) != 0) {
            requireControlPayloadSize(payloadLen);
        }
        var header = header(firstByte, payloadLen);
        OutputStream output = java.util.Objects.requireNonNull(outputStream);
        IOException failure = null;
        writeLock.lock();
        try {
            throwStoredWriteFailure();
            if (expectedState != null && messageWritingState != expectedState) {
                throw new IllegalStateException("Expected state " + expectedState + " but was " + messageWritingState);
            }
            if (closeSent) {
                throw new IllegalStateException("Cannot write websocket messages after close frame sent");
            }
            try {
                output.write(header, 0, header.length);
                if (payloadLen > 0) {
                    output.write(java.util.Objects.requireNonNull(payload), payloadOffset, payloadLen);
                }
                output.flush();
                if (endState != null) {
                    messageWritingState = endState;
                }
            } catch (IOException e) {
                writeFailure = e;
                messageWritingState = MessageWritingState.ERROR;
                failure = e;
            }
        } finally {
            writeLock.unlock();
        }
        if (failure != null) {
            enqueueApplicationError(failure, WebsocketSessionState.ERRORED);
            throw failure;
        }
    }

    private void throwStoredWriteFailure() throws IOException {
        if (writeFailure != null) {
            throw new IOException("Cannot write websocket messages after a previous write failed", writeFailure);
        }
    }

    private byte[] header(byte type, int payloadLength) {
        if (payloadLength <= 125) {
            // 1-byte case
            return new byte[] { type, (byte) payloadLength };
        } else if (payloadLength <= 65535) {
            // 3-byte case (first byte 126 + 2 bytes for length)
            return new byte[] {
                type,
                (byte) 126,
                (byte) ((payloadLength >> 8) & 0xFF),   // Higher byte
                (byte) (payloadLength & 0xFF)           // Lower byte
            };
        } else {
            // 9-byte case (first byte 127 + 8 bytes for length)
            return new byte[] {
                type,
                (byte) 127,
                (byte) 0,
                (byte) 0,
                (byte) 0,
                (byte) 0,
                (byte) ((payloadLength >> 24) & 0xFF),
                (byte) ((payloadLength >> 16) & 0xFF),
                (byte) ((payloadLength >> 8) & 0xFF),
                (byte) (payloadLength & 0xFF)
            };
        }
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return httpConnection.remoteAddress();
    }

    @Override
    public WebsocketSessionState state() {
        return lifecycle.state();
    }

    @Override
    public @Nullable Long pongLatencyMillis(ByteBuffer pongPayload) {
        if (pongPayload == null) throw new NullPointerException("pongPayload");
        WebsocketPingTracker tracker = pingTracker;
        return tracker == null ? null : tracker.pongLatencyMillis(pongPayload);
    }

    @Override
    public String toString() {
        return "WebsocketConnection{" +
            "state=" + lifecycle.state() +
            ", remote=" + remoteAddress() +
            '}';
    }
}
