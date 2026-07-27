package io.muserver;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final MuWebSocket webSocket;
    private final boolean eventsRunOnConnectionTask;
    private final Queue<ApplicationEventTask> applicationEvents = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean applicationEventRunnerScheduled = new AtomicBoolean();
    private final AtomicBoolean errorEventQueued = new AtomicBoolean();
    private final AtomicBoolean serverShutdownRequested = new AtomicBoolean();
    private volatile boolean closeReceived = false;
    private volatile boolean closeSent = false;
    private final Lock writeLock = new ReentrantLock();
    private final @Nullable WebsocketPingTracker pingTracker;
    private ReadState readState = ReadState.NONE;
    private volatile @Nullable ScheduledFuture<?> pingFuture;
    private volatile @Nullable Thread connectionTaskThread;

    private enum ReadState {
        NONE, TEXT, BINARY,
        /**
         * We are reading a fragmented message of a type we don't recognise, which is fine. We ignore it.
         */
        UNKNOWN
    }

    @FunctionalInterface
    private interface ApplicationEvent {
        void run() throws Exception;
    }

    private static final class ApplicationEventTask {
        private final ApplicationEvent event;
        private final CompletableFuture<@Nullable Void> completion = new CompletableFuture<>();

        private ApplicationEventTask(ApplicationEvent event) {
            this.event = event;
        }
    }

    WebsocketConnection(Http1Connection httpConnection, MuWebSocket webSocket, WebSocketHandlerBuilder.Settings settings) {
        this.httpConnection = httpConnection;
        this.server = httpConnection.serverImpl();
        this.webSocket = webSocket;
        this.settings = settings;
        this.eventsRunOnConnectionTask = httpConnection.webSocketEventsRunOnConnectionTask();
        if (settings.pingIntervalMillis == 0) {
            pingTracker = null;
        } else {
            pingTracker = new WebsocketPingTracker();
        }

    }

    MuWebSocket webSocket() {
        return webSocket;
    }

    ExecutorService asyncExecutor() {
        return server.asyncExecutor();
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

    public void runAndBlockUntilDone(InputStream inputStream, OutputStream outputStream, byte[] readBuffer) throws Exception {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.buffer = ByteBuffer.wrap(readBuffer).flip();
        connectionTaskThread = Thread.currentThread();

        try {
            lifecycle.onConnected();
            invokeApplicationEvent(() -> webSocket.onConnect(this));

            if (settings.pingIntervalMillis > 0) {
                startPinging();
            }

            long messageLength = 0;
            while (!closeReceived) {

                // make sure we at least have the minimum sized buffer
                readAtLeast(2);
                int firstByte = buffer.get() & 0xFF;
                boolean fin = (firstByte & 0x80) != 0;
                boolean rsv1 = (firstByte & 0x40) != 0;
                boolean rsv2 = (firstByte & 0x20) != 0;
                boolean rsv3 = (firstByte & 0x10) != 0;
                if (rsv1 || rsv2 || rsv3) {
                    throw frameError(1002, "Unsupported websocket reserved keywords");
                }

                int opcode = firstByte & 0x0F;

                int secondByte = buffer.get() & 0xFF;
                boolean masked = (secondByte & 0x80) != 0;
                long payloadLength = secondByte & 0b01111111;

                if (!masked) {
                    throw frameError(1002, "Unmasked client data");
                }
                if (payloadLength == 126) {
                    readAtLeast(2);
                    payloadLength = buffer.getShort() & 0xFFFF;
                } else if (payloadLength == 127) {
                    readAtLeast(8);
                    payloadLength = buffer.getLong();
                }
                if (payloadLength < 0) {
                    throw frameError(1002, "Invalid payload length");
                }

                boolean controlFrame = (opcode & 0x08) != 0;
                if (controlFrame) {
                    if (!fin) {
                        throw frameError(1002, "Fragmented control frame");
                    }
                    if (payloadLength > 125) {
                        throw frameError(1002, "Control frame payload cannot exceed 125 bytes");
                    }
                }
                if (payloadLength > settings.maxFramePayloadLength) {
                    throw frameError(1009, "Max payload length of " + settings.maxFramePayloadLength + " exceeded with frame size " + payloadLength);
                }
                if (messageLength + payloadLength > settings.maxMessageLength) {
                    throw frameError(1009, "Max message length of " + settings.maxMessageLength + " exceeded");
                }

                byte[] maskingKey = new byte[4];
                readAtLeast(4);
                buffer.get(maskingKey, 0, 4);

                // in practice, the max length is an int so fits in a byte array
                int payloadLen = (int) payloadLength;
                var slice = readAndUnmaskPayload(payloadLen, maskingKey);


                if (closeReceived) {
                    log.info("Ignoring " + opcode + " message as close received already");
                } else if (opcode == 0x0) {
                    // continuation frame
                    messageLength += payloadLength;
                    if (readState == ReadState.TEXT) {
                        invokeApplicationEvent(() -> webSocket.onTextFragment(slice, fin));
                    } else if (readState == ReadState.BINARY) {
                        invokeApplicationEvent(() -> webSocket.onBinaryFragment(slice, fin));
                    } else if (readState != ReadState.UNKNOWN) {
                        throw frameError(1002, "Continuation frame received unexpectedly");
                    }
                    if (fin) {
                        messageLength = 0L;
                        readState = ReadState.NONE;
                    }
                } else if (opcode == 0x1) {
                    // text frame
                    if (readState != ReadState.NONE) {
                        throw frameError(1002, "New text message sent while expecting continuation frame");
                    }
                    messageLength = payloadLength;
                    if (fin) {
                        var text = StandardCharsets.UTF_8.newDecoder().decode(slice).toString();
                        invokeApplicationEvent(() -> webSocket.onText(text));
                    } else {
                        readState = ReadState.TEXT;
                        invokeApplicationEvent(() -> webSocket.onTextFragment(slice, false));
                    }
                } else if (opcode == 0x2) {
                    // binary frame
                    if (readState != ReadState.NONE) {
                        throw frameError(1002, "New binary message received while expecting continuation frame");
                    }
                    messageLength = payloadLength;
                    if (fin) {
                        invokeApplicationEvent(() -> webSocket.onBinary(slice));
                    } else {
                        readState = ReadState.BINARY;
                        invokeApplicationEvent(() -> webSocket.onBinaryFragment(slice, false));
                    }
                } else if (opcode == 0x8) {
                    if (payloadLen == 1) {
                        throw frameError(1002, "Close frame payload of 1 byte is invalid");
                    }
                    lifecycle.onClientCloseStarted();
                    closeReceived = true;
                    // close frame
                    short closeCode;
                    String reason = "";
                    if (payloadLen >= 2) {
                        closeCode = slice.getShort();
                        if (slice.hasRemaining()) {
                            reason = StandardCharsets.UTF_8.decode(slice).toString();
                        }
                    } else {
                        closeCode = 1005;
                    }
                    log.info("Client close: " + closeCode + " " + reason);
                    String closeReason = reason;
                    invokeApplicationEvent(() -> webSocket.onClientClosed(closeCode, closeReason));
                    completeCloseHandshakeIfCloseSent();
                } else if (opcode == 0x9) {
                    invokeApplicationEvent(() -> webSocket.onPing(slice));
                } else if (opcode == 0xA) {
                    invokeApplicationEvent(() -> webSocket.onPong(slice));
                } else if (!fin) {
                    // ignore unknown types, but do allow continuation frames for them
                    readState = ReadState.UNKNOWN;
                }

            }

            // it's finished - the TCP connection will be closed
        } catch (Throwable e) {
            if (!serverShutdownRequested.get()
                && !errorEventQueued.get()
                && lifecycle.state() != WebsocketSessionState.TIMED_OUT) {
                WebsocketSessionState errorState =
                    e instanceof TimeoutException || e instanceof SocketTimeoutException
                        ? WebsocketSessionState.TIMED_OUT
                        : WebsocketSessionState.ERRORED;
                invokeApplicationError(e, errorState);
            }
        } finally {
            if (eventsRunOnConnectionTask) {
                drainApplicationEvents();
            }
            ScheduledFuture<?> currentPing = pingFuture;
            if (currentPing != null) {
                currentPing.cancel(false);
                pingFuture = null;
            }
            connectionTaskThread = null;
        }
    }

    private void unmask(ByteBuffer buffer, byte[] maskingKey, int payloadLength) {
        var offset = buffer.position();
        for (int i = 0; i < payloadLength; i++) {
            byte mask = maskingKey[i % 4];
            int pos = offset + i;
            byte maskedB = buffer.get(pos);
            byte unmaskedB = (byte)(maskedB ^ mask);
            buffer.put(pos, unmaskedB);
        }
    }

    private ByteBuffer readAndUnmaskPayload(int len, byte[] maskingKey) throws IOException {
        ByteBuffer readBuffer = java.util.Objects.requireNonNull(buffer);
        if (len <= readBuffer.capacity()) {
            readAtLeast(len);
            unmask(readBuffer, maskingKey, len);
            var tempLimit = readBuffer.limit();
            readBuffer.limit(readBuffer.position() + len);
            var slice = readBuffer.slice();
            readBuffer.position(readBuffer.limit());
            readBuffer.limit(tempLimit);
            return slice;
        } else {
            var full = ByteBuffer.allocate(len);
            var toRead = len;
            while (toRead > 0) {
                int nextLen = Math.min(toRead, readBuffer.capacity());
                var slice = readAndUnmaskPayload(nextLen, maskingKey);
                full.put(slice);
                toRead = toRead - nextLen;
            }
            return full.flip();
        }
    }

    private void readAtLeast(int minBytes) throws IOException {
        ByteBuffer readBuffer = java.util.Objects.requireNonNull(buffer);
        InputStream input = java.util.Objects.requireNonNull(inputStream);
        if (minBytes > readBuffer.capacity()) throw new IllegalArgumentException("This buffer is not big enough");
        while (readBuffer.remaining() < minBytes) {
            if (readBuffer.capacity() - readBuffer.limit() < minBytes) {
                readBuffer.compact().flip();
            }
            int read = input.read(readBuffer.array(), readBuffer.arrayOffset() + readBuffer.position(),
                readBuffer.capacity() - readBuffer.limit());
            if (read == -1) {
                throw new ClientDisconnectedException();
            }
            readBuffer.limit(readBuffer.limit() + read);
        }
    }

    private Exception frameError(int code, String reason) throws IOException {
        close(code, reason);
        return new ProtocolException(reason);
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
                    log.info("Error while shutting down WebSocket", failure);
                    httpConnection.forceShutdown();
                }
            });
    }

    private void invokeApplicationEvent(ApplicationEvent event) throws Exception {
        if (eventsRunOnConnectionTask) {
            try {
                callApplicationEvent(event);
            } finally {
                // A terminal event can be queued by a write attempted inside a callback.
                // Drain it before returning to the blocking socket read.
                drainApplicationEvents();
            }
            return;
        }
        CompletableFuture<@Nullable Void> completion = enqueueApplicationEvent(event);
        try {
            completion.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new MuException("Unexpected WebSocket callback failure", cause);
        }
    }

    private void callApplicationEvent(ApplicationEvent event) throws Exception {
        try {
            server.callHandlerApplicationTask(() -> {
                event.run();
                return null;
            });
        } catch (Exception | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new MuException("Unexpected WebSocket callback failure", failure);
        }
    }

    private void invokeApplicationError(Throwable cause, WebsocketSessionState errorState) throws Exception {
        if (errorEventQueued.compareAndSet(false, true)) {
            invokeApplicationEvent(errorEvent(cause, errorState));
        }
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
        applicationEvents.add(task);
        if (eventsRunOnConnectionTask) {
            if (Thread.currentThread() != connectionTaskThread) {
                // A shared single-thread executor cannot run another task while its
                // connection reader is blocked. Terminal external events wake that reader,
                // which drains this mailbox from its finally block.
                httpConnection.wakeWebSocketReader();
            }
        } else {
            scheduleApplicationEventRunner();
        }
        return task.completion;
    }

    private void scheduleApplicationEventRunner() {
        if (!applicationEventRunnerScheduled.compareAndSet(false, true)) {
            return;
        }
        RejectedExecutionException rejected = server.tryExecuteHandlerTask(this::runApplicationEvents);
        if (rejected != null) {
            failApplicationEvents(rejected);
        }
    }

    private void failApplicationEvents(RejectedExecutionException failure) {
        applicationEventRunnerScheduled.set(false);
        ApplicationEventTask task;
        while ((task = applicationEvents.poll()) != null) {
            task.completion.completeExceptionally(failure);
        }
    }

    private void runApplicationEvents() {
        while (true) {
            drainApplicationEvents();
            applicationEventRunnerScheduled.set(false);
            if (applicationEvents.isEmpty()
                || !applicationEventRunnerScheduled.compareAndSet(false, true)) {
                return;
            }
        }
    }

    private void drainApplicationEvents() {
        ApplicationEventTask task;
        while ((task = applicationEvents.poll()) != null) {
            try {
                callApplicationEvent(task.event);
                task.completion.complete(null);
            } catch (Throwable t) {
                task.completion.completeExceptionally(t);
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
        payload = arrayBuffer(payload);
        writeFragment((byte)0b10001001, payload.array(), payload.arrayOffset() + payload.position(), payload.remaining(), null, null);
    }

    @Override
    public void sendPong(ByteBuffer payload) throws IOException {
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
        writeLock.lock();
        try {
            lifecycle.onServerCloseStarted();
            if (reason == null || reason.isEmpty()) {
                byte[] closeCodeBytes = new byte[2];
                closeCodeBytes[0] = (byte) ((statusCode >> 8) & 0xFF);
                closeCodeBytes[1] = (byte) (statusCode & 0xFF);
                writeFragment((byte)0b10001000, closeCodeBytes, 0, 2, null, null);
            } else {
                var reasonBytes = reason.getBytes(StandardCharsets.UTF_8);
                var payload = new byte[reasonBytes.length + 2];
                payload[0] = (byte) ((statusCode >> 8) & 0xFF);
                payload[1] = (byte) (statusCode & 0xFF);
                System.arraycopy(reasonBytes, 0, payload, 2, reasonBytes.length);
                writeFragment((byte)0b10001000, payload, 0, payload.length, null, null);
            }
            if (!closeSent) {
                closeSent = true;
            }
        } finally {
            writeLock.unlock();
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
