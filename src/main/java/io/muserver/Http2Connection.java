package io.muserver;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

class Http2Connection extends BaseHttpConnection implements Http2Peer {
    private static final int MAX_POSSIBLE_STREAM_ID = 0x7fffffff;
    static final long GO_AWAY_GRACE_PERIOD_MILLIS = 200;
    private static final Http2GoAway GO_AWAY_WARNING = new Http2GoAway(MAX_POSSIBLE_STREAM_ID, Http2ErrorCode.NO_ERROR.code(), null);

    private enum HState {
        ACTIVE(true), SHUTDOWN_INITIATED(true), COMPLETED(false), ERRORED(false);
        final boolean canSendFrames;
        /**
         * @param canSendFrames if true then frames can still be sent/received
         */
        HState(boolean canSendFrames) {
            this.canSendFrames = canSendFrames;
        }
    }
    /**
     * The inbound side of the connection. This is tracked separately from writes because the peer can stop sending
     * new work while we still have responses to finish.
     */
    private volatile HState readState =  HState.ACTIVE;
    /**
     * The outbound side of the connection. This is tracked separately from reads because we can start shutting down
     * locally while still reading frames for streams that are already in flight.
     */
    private volatile HState writeState =  HState.ACTIVE;

    private static final Logger log = LoggerFactory.getLogger(Http2Connection.class);

    private final Http2Settings serverSettings;
    // Immutable reader-published snapshot used by application and writer domains.
    private volatile Http2Settings clientSettings = Http2Settings.DEFAULT_CLIENT_SETTINGS;
    private final ByteBuffer buffer;
    // The writer freezes this boundary when it queues final GOAWAY; the reader
    // observes it before dispatching subsequent frames.
    private volatile int maxAllowedStreamId = MAX_POSSIBLE_STREAM_ID;
    // State-lock-owned admission and shutdown data. The reader is the only code
    // that reads lastStreamId without the lock, and it is also the only writer.
    private int lastStreamId;
    private int peerGoAwayLastStreamId = MAX_POSSIBLE_STREAM_ID;
    private final Http2InboundFlowControl inboundFlowControl = new Http2InboundFlowControl(65_535);
    private final ConcurrentLinkedQueue<PendingSettingsAck> settingsAckQueue = new ConcurrentLinkedQueue<>();
    private final Http2StreamRegistry streamRegistry = new Http2StreamRegistry();
    private final ExecutorService handlerExecutor;
    private final ExecutorService writerExecutor;
    private final long settingsAckTimeoutMillis;

    private final Lock stateLock = new ReentrantLock();
    private final Http2WriteCoordinator writeCoordinator;
    private final AtomicBoolean writerTaskScheduled = new AtomicBoolean();
    private final CompletableFuture<@Nullable Void> writeLoopEnded = new CompletableFuture<>();
    private volatile @Nullable OutputStream writerOutput;
    private boolean initialGoAwayWritten;
    private boolean finalGoAwayQueued;
    private long finalGoAwayEarliestNanos;
    private boolean finalGoAwayWakeScheduled;

    private static final class PendingSettingsAck {
        private final AtomicBoolean pending = new AtomicBoolean(true);
        private volatile @Nullable ScheduledFuture<?> timeoutTask;

        private boolean acknowledge() {
            if (!pending.compareAndSet(true, false)) {
                return false;
            }
            ScheduledFuture<?> currentTimeoutTask = timeoutTask;
            if (currentTimeoutTask != null) {
                currentTimeoutTask.cancel(false);
            }
            return true;
        }

        private boolean markTimedOut() {
            return pending.compareAndSet(true, false);
        }

        private void timeoutTask(ScheduledFuture<?> task) {
            timeoutTask = task;
            if (!pending.get()) {
                task.cancel(false);
            }
        }
    }

    Http2Connection(Mu3ServerImpl server, ConnectionAcceptor creator, Socket clientSocket, @Nullable Certificate clientCertificate, Instant handshakeStartTime, Http2Settings initialServerSettings, long settingsAckTimeoutMillis, ExecutorService handlerExecutor, ExecutorService writerExecutor) {
        super(server, creator, clientSocket, clientCertificate, handshakeStartTime);
        this.serverSettings = initialServerSettings;
        this.settingsAckTimeoutMillis = settingsAckTimeoutMillis;
        this.handlerExecutor = handlerExecutor;
        this.writerExecutor = writerExecutor;
        this.writeCoordinator = new Http2WriteCoordinator(65535, this::requestWriteRun);
        this.buffer = ByteBuffer.allocate(serverSettings.maxFrameSize).flip();
    }

    @Override
    public int maxFrameSize() {
        return clientSettings.maxFrameSize;
    }

    @Override
    public FieldBlockEncoder fieldBlockEncoder() {
        return writeCoordinator.fieldBlockEncoder();
    }

    private void reserveInboundCredit(int streamId, int amount) throws Http2Exception {
        applyInboundFlowResult(inboundFlowControl.reserve(streamId, amount));
    }

    void returnInboundCredit(int streamId, int amount, boolean includeStream) throws Http2Exception {
        if (amount <= 0) {
            return;
        }
        applyInboundFlowResult(
            inboundFlowControl.returnCredit(streamId, amount, includeStream)
        );
    }

    private void applyInboundFlowResult(Http2InboundFlowControl.Result result)
        throws Http2Exception {
        if (result.connectionUpdate() > 0 || result.streamUpdate() > 0) {
            stateLock.lock();
            try {
                if (writeState.canSendFrames) {
                    if (result.connectionUpdate() > 0) {
                        writeCoordinator.submit(new WriteTask(
                            new Http2WindowUpdate(0, result.connectionUpdate()),
                            false
                        ));
                    }
                    if (result.streamUpdate() > 0) {
                        writeCoordinator.submit(new WriteTask(
                            new Http2WindowUpdate(result.streamId(), result.streamUpdate()),
                            false
                        ));
                    }
                }
            } finally {
                stateLock.unlock();
            }
        }
        Http2Exception error = result.error();
        if (error != null) {
            throw error;
        }
    }

    void write(LogicalHttp2Frame frame) {
        write(new WriteTask(frame, false));
    }

    void write(WriteTask writeTask) {
        stateLock.lock();
        try {
            writeLocked(writeTask);
        } finally {
            stateLock.unlock();
        }
    }

    private void writeLocked(LogicalHttp2Frame frame) {
        writeLocked(new WriteTask(frame, false));
    }

    // The caller holds stateLock, making admission and command publication one transition.
    private void writeLocked(WriteTask writeTask) {
        if (writeState.canSendFrames && canWriteFrame(writeTask.frame())) {
            LogicalHttp2Frame frame = writeTask.frame();
            boolean retireRejectedStream = frame instanceof Http2ResetStreamFrame
                && streamRegistry.removeRejectedRequestBody(frame.streamId());
            boolean retainResetState = frame instanceof Http2ResetStreamFrame
                && streamRegistry.containsApplicationStream(frame.streamId());
            if (frame instanceof Http2ResetStreamFrame) {
                inboundFlowControl.closeStream(frame.streamId());
            }
            writeCoordinator.submit(writeTask, retainResetState);
            if (retireRejectedStream) {
                writeCoordinator.forgetStream(frame.streamId());
            }
        } else {
            writeTask.fail(new IOException("HTTP/2 connection or stream is closed"));
        }
    }

    private boolean canWriteFrame(LogicalHttp2Frame frame) {
        int streamId = frame.streamId();
        if (streamId == 0 || frame instanceof Http2ResetStreamFrame) {
            return true;
        }
        Http2Stream stream = streamRegistry.applicationStream(streamId);
        return stream == null
            ? streamId > lastStreamId
            : !stream.resetWasInitiated();
    }

    private void signalWriteLoop() {
        writeCoordinator.wakeUp();
    }

    private void resetPendingWritesForStream(
        Http2ResetStreamFrame resetFrame,
        IOException reason,
        @Nullable Http2Stream stream
    ) throws Http2Exception {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                boolean retireRejectedStream =
                    streamRegistry.removeRejectedRequestBody(resetFrame.streamId());
                inboundFlowControl.closeStream(resetFrame.streamId());
                writeCoordinator.resetStream(resetFrame, reason, stream);
                if (retireRejectedStream) {
                    writeCoordinator.forgetStream(resetFrame.streamId());
                }
                return;
            }
            throw Http2Exception.connection(Http2ErrorCode.INTERNAL_ERROR, "HTTP/2 writer closed before peer reset was processed");
        } finally {
            stateLock.unlock();
        }
    }

    private void queueRejectedResponse(
        int streamId,
        int initialCredit,
        Http2StreamState initialState,
        Http2HeadersFrame headers,
        Http2DataFrame body,
        @Nullable Http2Stream replacedApplicationStream
    ) {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                // Rejected headers do not create an application-facing Http2Stream, so queue
                // their complete protocol exchange directly as one ordered coordinator transaction.
                if (initialState.canReceiveEndStream()) {
                    if (replacedApplicationStream == null) {
                        streamRegistry.registerRejectedRequestBody(streamId);
                    } else {
                        streamRegistry.convertApplicationStreamToRejectedRequestBody(
                            replacedApplicationStream
                        );
                    }
                } else if (replacedApplicationStream != null) {
                    streamRegistry.removeApplicationStream(replacedApplicationStream);
                }
                if (replacedApplicationStream != null) {
                    inboundFlowControl.closeStream(streamId);
                }
                inboundFlowControl.openStream(streamId, serverSettings.initialWindowSize);
                writeCoordinator.openStream(
                    streamId,
                    initialCredit,
                    initialState,
                    null
                );
                writeCoordinator.submit(new WriteTask(headers, false));
                writeCoordinator.submit(new WriteTask(body, false));
                if (!initialState.canReceiveEndStream()) {
                    inboundFlowControl.closeStream(streamId);
                    writeCoordinator.forgetStream(streamId);
                }
            } else if (replacedApplicationStream != null) {
                inboundFlowControl.closeStream(streamId);
                streamRegistry.removeApplicationStream(replacedApplicationStream);
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void finishRejectedRequestBody(int streamId) throws Http2Exception {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                streamRegistry.removeRejectedRequestBody(streamId);
                inboundFlowControl.closeStream(streamId);
                writeCoordinator.remoteEndStream(streamId);
                writeCoordinator.forgetStream(streamId);
                return;
            }
            throw Http2Exception.connection(Http2ErrorCode.INTERNAL_ERROR, "HTTP/2 writer closed before rejected request ended");
        } finally {
            stateLock.unlock();
        }
    }

    void remoteEndStream(int streamId) throws Http2Exception {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                writeCoordinator.remoteEndStream(streamId);
                return;
            }
            throw Http2Exception.connection(Http2ErrorCode.INTERNAL_ERROR, "HTTP/2 writer closed before END_STREAM was processed");
        } finally {
            stateLock.unlock();
        }
    }

    private void applicationExchangeEndedForWrites(int streamId) {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                writeCoordinator.applicationExchangeEnded(streamId);
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void applyConnectionWindowUpdate(int increment) throws Http2Exception {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                writeCoordinator.applyConnectionWindowUpdate(increment, lastStreamId);
                return;
            }
            throw Http2Exception.connection(Http2ErrorCode.INTERNAL_ERROR, "HTTP/2 writer closed before WINDOW_UPDATE was processed");
        } finally {
            stateLock.unlock();
        }
    }

    private void applyStreamWindowUpdate(int streamId, int increment) throws Http2Exception {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                writeCoordinator.applyStreamWindowUpdate(streamId, increment);
                return;
            }
            throw Http2Exception.connection(Http2ErrorCode.INTERNAL_ERROR, "HTTP/2 writer closed before WINDOW_UPDATE was processed");
        } finally {
            stateLock.unlock();
        }
    }

    private void applyPeerSettingsChange(Http2Settings oldSettings, Http2Settings newSettings)
        throws Http2Exception {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                writeCoordinator.applyPeerSettingsChange(
                    newSettings.initialWindowSize - oldSettings.initialWindowSize,
                    newSettings.headerTableSize,
                    new WriteTask(Http2Settings.ACK, false),
                    lastStreamId
                );
                return;
            }
            throw Http2Exception.connection(Http2ErrorCode.INTERNAL_ERROR, "HTTP/2 writer closed before SETTINGS was processed");
        } finally {
            stateLock.unlock();
        }
    }

    void initializeHandshakePeerSettings(Http2Settings settings) {
        if (writerOutput != null) {
            throw new IllegalStateException("The HTTP/2 writer has already started");
        }
        clientSettings = settings;
        writeCoordinator.initializePeerHeaderTableSize(settings.headerTableSize);
    }

    private void failConnection(WriteTask goAway, IOException reason) {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                writeCoordinator.failConnection(goAway, reason);
            } else {
                goAway.fail(new IOException("HTTP/2 writer closed before GOAWAY was processed", reason));
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void closeSocketQuietly() {
        if (closed.compareAndSet(false, true)) {
            try {
                clientSocket.close();
            } catch (IOException e) {
                log.debug("Error closing HTTP/2 socket", e);
            }
        }
    }

    private void queuePendingSettingsAck() throws IOException {
        var pendingAck = new PendingSettingsAck();
        settingsAckQueue.add(pendingAck);
        try {
            pendingAck.timeoutTask(server.scheduleTimerCallback(
                () -> {
                    if (pendingAck.markTimedOut()) {
                        writeCoordinator.settingsTimedOut();
                    }
                },
                settingsAckTimeoutMillis,
                TimeUnit.MILLISECONDS
            ));
        } catch (RuntimeException | Error e) {
            settingsAckQueue.remove(pendingAck);
            pendingAck.acknowledge();
            throw new IOException("Could not schedule SETTINGS acknowledgement timeout", e);
        }
    }

    private void cancelPendingSettingsAckTimeouts() {
        PendingSettingsAck pendingAck;
        while ((pendingAck = settingsAckQueue.poll()) != null) {
            pendingAck.acknowledge();
        }
    }

    private void setReadStateAndSignal(HState newState) {
        stateLock.lock();
        try {
            readState = newState;
        } finally {
            stateLock.unlock();
        }
        signalWriteLoop();
    }

    private void setReadStateIfActiveAndSignal(HState newState) {
        boolean changed;
        stateLock.lock();
        try {
            changed = readState.canSendFrames;
            if (changed) {
                readState = newState;
            }
        } finally {
            stateLock.unlock();
        }
        if (changed) {
            signalWriteLoop();
        }
    }

    private void failAfterUnexpectedInputEnd(IOException reason) {
        stateLock.lock();
        try {
            markConnectionErroredLocked();
        } finally {
            stateLock.unlock();
        }
        for (Http2Stream stream : streamRegistry.applicationStreams()) {
            stream.cancel(reason, false);
        }
        signalWriteLoop();
    }

    private void markPeerShutdownInitiatedLocked() {
        readState = HState.SHUTDOWN_INITIATED;
    }

    private void markLocalShutdownInitiatedLocked() {
        if (writeState == HState.ACTIVE) {
            log.info("Graceful shutdown initiated with write state {}", writeState);
            writeState = HState.SHUTDOWN_INITIATED;
            // As per: https://datatracker.ietf.org/doc/html/rfc9113#section-6.8-18
            // A server that is attempting to gracefully shut down a connection SHOULD send an initial GOAWAY frame
            // with the last stream identifier set to 2^31-1 and a NO_ERROR code. This signals to the client that a
            // shutdown is imminent and that initiating further requests is prohibited. After allowing time for any
            // in-flight stream creation (at least one round-trip time), the server MAY send another GOAWAY frame
            // with an updated last stream identifier. This ensures that a connection can be cleanly shut down
            // without losing requests.
            writeLocked(GO_AWAY_WARNING);
        }
    }

    private void markConnectionErroredLocked() {
        readState = HState.ERRORED;
        writeState = HState.ERRORED;
    }

    private boolean isLocalShutdownInitiatedLocked() {
        return writeState == HState.SHUTDOWN_INITIATED;
    }

    private boolean isPeerShutdownInitiatedLocked() {
        return readState == HState.SHUTDOWN_INITIATED;
    }

    private boolean isReadTerminalLocked() {
        return !readState.canSendFrames;
    }

    private boolean isShutdownInitiatedLocked() {
        return isLocalShutdownInitiatedLocked() || isPeerShutdownInitiatedLocked();
    }

    private long localGoAwayGracePeriodNanosRemainingLocked(long now) {
        if (!initialGoAwayWritten) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, finalGoAwayEarliestNanos - now);
    }

    private boolean isStillAllowingInFlightStreamCreationLocked(long now) {
        return isLocalShutdownInitiatedLocked()
            && localGoAwayGracePeriodNanosRemainingLocked(now) > 0L;
    }

    private boolean canStartNewStreamsLocked(long now) {
        return readState.canSendFrames
            && writeState.canSendFrames
            && !isPeerShutdownInitiatedLocked()
            && (!isLocalShutdownInitiatedLocked() || isStillAllowingInFlightStreamCreationLocked(now));
    }

    private boolean shouldQueueFinalGoAwayLocked(long now) {
        if (finalGoAwayQueued) {
            return false;
        }
        if (isLocalShutdownInitiatedLocked()) {
            return initialGoAwayWritten
                && localGoAwayGracePeriodNanosRemainingLocked(now) == 0L;
        }
        return isPeerShutdownInitiatedLocked()
            && streamRegistry.isEmpty();
    }

    private void queueFinalGoAwayLocked() {
        finalGoAwayQueued = true;
        maxAllowedStreamId = lastStreamId;
        log.info("Queuing final go away with last stream id {}", maxAllowedStreamId);
        writeLocked(new Http2GoAway(maxAllowedStreamId, 0, null));
    }

    private boolean isTerminalAndDrainedLocked() {
        return (isReadTerminalLocked() || (isShutdownInitiatedLocked() && finalGoAwayQueued))
            && streamRegistry.isEmpty()
            && writeCoordinator.isIdle();
    }

    private long nanosUntilNextWriteActionLocked(long now) {
        if (isLocalShutdownInitiatedLocked()
            && initialGoAwayWritten
            && !finalGoAwayQueued) {
            return localGoAwayGracePeriodNanosRemainingLocked(now);
        }
        return -1L;
    }

    private void recordInitialGoAwayWritten() {
        stateLock.lock();
        try {
            if (isLocalShutdownInitiatedLocked() && !initialGoAwayWritten) {
                initialGoAwayWritten = true;
                finalGoAwayEarliestNanos = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(GO_AWAY_GRACE_PERIOD_MILLIS);
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void completeShutdownLocked() {
        log.info("HTTP/2 connection finished with read state {} and write state {}", readState, writeState);
        writeState = readState == HState.ERRORED ? HState.ERRORED : HState.COMPLETED;
        readState = writeState;
    }

    private boolean drainWritableFrames(OutputStream clientOut) throws IOException {
        Http2WriteCoordinator.WritableFrame candidate;
        while ((candidate = writeCoordinator.pollWritable()) != null) {
            try {
                Http2Exception protocolError = candidate.protocolError();
                LogicalHttp2Frame frame = protocolError == null
                    ? candidate.frame()
                    : prepareCoordinatorErrorFrame(protocolError);
                log.info("Writing {}", frame);
                frame.writeTo(this, clientOut);
                if (frame instanceof Http2Settings) {
                    var settings = (Http2Settings) frame;
                    if (!settings.isAck) {
                        queuePendingSettingsAck();
                    }
                }
                clientOut.flush();
                if (GO_AWAY_WARNING.equals(frame)) {
                    recordInitialGoAwayWritten();
                }
                candidate.complete();
                if (protocolError != null && protocolError.errorType() == Http2Level.CONNECTION) {
                    return true;
                }
            } catch (IOException e) {
                candidate.fail(e);
                throw e;
            }
        }
        return false;
    }

    private LogicalHttp2Frame prepareCoordinatorErrorFrame(Http2Exception error) {
        IOException reason = new IOException(
            error.errorType() == Http2Level.CONNECTION ? "Connection error" : "Stream error",
            error
        );
        if (error.errorType() == Http2Level.STREAM) {
            Http2Stream stream = streamRegistry.applicationStream(error.streamId());
            if (stream != null) {
                stream.cancel(reason);
            }
            return new Http2ResetStreamFrame(error.streamId(), error.errorCode().code());
        }

        int acceptedLastStreamId;
        stateLock.lock();
        try {
            // The reader does not wait for flow-control validation. Freeze stream acceptance
            // before materializing GOAWAY so its last-stream ID still describes every request
            // that could have been dispatched while the command was in the mailbox.
            markConnectionErroredLocked();
            acceptedLastStreamId = lastStreamId;
        } finally {
            stateLock.unlock();
        }
        for (Http2Stream stream : streamRegistry.applicationStreams()) {
            stream.cancel(reason, false);
        }
        return new Http2GoAway(acceptedLastStreamId, error.errorCode().code(), null);
    }

    @Override
    public void start(InputStream clientIn, OutputStream clientOut) throws Http2Exception, IOException, ExecutionException, InterruptedException, TimeoutException {
        Future<?> writeEndedFuture = null;
        try {
            // do the handshake
            clientSettings = Http2Handshaker.handshake(this, serverSettings, clientSettings, buffer, clientIn, clientOut);

            queuePendingSettingsAck();

            var fieldBlockDecoder = new FieldBlockDecoder(new HpackTable(serverSettings.headerTableSize), server.maxUrlSize(), server.maxRequestHeadersSize());
            writeEndedFuture = startWriteLoop(clientOut);

            // and now just read frames
            while (readState.canSendFrames) {
                Http2FrameHeader currentFrameHeader = null;
                boolean readingFrameHeader = true;
                try {
                    Mutils.readAtLeast(buffer, clientIn, Http2FrameHeader.FRAME_HEADER_LENGTH);

                    currentFrameHeader = Http2FrameHeader.readFrom(buffer);
                    readingFrameHeader = false;
                    var fh = currentFrameHeader;
                    var len = fh.length();
                    Mutils.readAtLeast(buffer, clientIn, len);
                    log.info("read fh = " + fh);

                    if (fh.streamId() > maxAllowedStreamId) {
                        // we've told the client we have stopped, but this is a new stream ID
                        log.info("Discarding " + fh.streamId() + " because we told the client the last stream ID is " + lastStreamId);
                        discardPayload(buffer, clientIn, len);
                        if (fh.frameType() == Http2FrameType.HEADERS) {
                            write(new Http2ResetStreamFrame(fh.streamId(), Http2ErrorCode.REFUSED_STREAM.code()));
                        }
                    } else {
                        switch (fh.frameType()) {
                            case HEADERS: {
                                readHeaders(clientIn, fh, fieldBlockDecoder);
                                break;
                            }
                            case DATA: {
                                readDataFrame(fh);
                                break;
                            }
                            case SETTINGS: {
                                readSettingsFrame(fh);
                                break;
                            }
                             case PING: {
                                 readPingFrame(fh);
                                 break;
                             }
                            case WINDOW_UPDATE: {
                                readWindowUpdate(fh);
                                break;
                            }
                            case GOAWAY: {
                                readGoAwayFrame(fh);
                                break;
                            }
                            case RST_STREAM: {
                                readResetStreamFrame(fh);
                                break;
                            }
                            case PRIORITY: {
                                readPriorityFrame(fh);
                                break;
                            }
                            case CONTINUATION: {
                                throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "Out of order continuation frame");
                            }
                            case PUSH_PROMISE: {
                                throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "Client sent push promise");
                            }
                            default: {
                                log.info("Discarding " + len + " bytes for unsupported type " + fh);
                                discardPayload(buffer, clientIn, len);
                            }
                        }
                    }
                } catch (Http2Exception h2e) {
                    if (h2e.errorType() == Http2Level.CONNECTION) {
                        throw h2e;
                    }
                    var stream = streamRegistry.applicationStream(h2e.streamId());
                    if (stream != null && !stream.peerResetWasRead()) {
                        stream.recordLocalResetFromReader();
                    }
                    // Queue the reset before cancellation wakes the request handler. Any
                    // response work triggered by the body failure is then ordered behind
                    // the reset and rejected by the coordinator.
                    write(new Http2ResetStreamFrame(h2e.streamId(), h2e.errorCode().code()));
                    if (stream != null && !stream.peerResetWasRead()) {
                        stream.cancel(new IOException("Stream error", h2e));
                    }
                } catch (EOFException e) {
                    boolean noActiveStreams = noProtocolStreamsAreActive();
                    if (readingFrameHeader && noActiveStreams) {
                        log.info("Client closed HTTP/2 connection while waiting for the next frame");
                        setReadStateIfActiveAndSignal(HState.COMPLETED);
                    } else {
                        String frameDetails = readingFrameHeader ? "frame header" : "frame payload for " + currentFrameHeader;
                        log.warn("EOF while reading {} at read state {} writeState={}", frameDetails, readState, writeState, e);
                        if (noActiveStreams) {
                            setReadStateIfActiveAndSignal(HState.COMPLETED);
                        } else {
                            failAfterUnexpectedInputEnd(new IOException("Client closed an active HTTP/2 connection", e));
                        }
                    }
                } catch (SocketException e) {
                    boolean noActiveStreams = noProtocolStreamsAreActive();
                    if (noActiveStreams) {
                        log.info("Socket closed while reading HTTP/2 frames at read state {} writeState={}: {}", readState, writeState, e.getMessage());
                        setReadStateIfActiveAndSignal(HState.COMPLETED);
                    } else {
                        log.warn("Socket exception while reading HTTP/2 frames at read state {} writeState={}", readState, writeState, e);
                        failAfterUnexpectedInputEnd(new IOException("Socket closed with active HTTP/2 streams", e));
                    }
                }
            }

            writeEndedFuture.get();
            log.info("write loop ended");

        } catch (Http2Exception h2e) {
            log.debug("HTTP2 error", h2e);

            var connectionError = new IOException("Connection error", h2e);

            try {
                Http2GoAway goAway = new Http2GoAway(lastStreamId, h2e.errorCode().code(), null);
                if (writeEndedFuture != null) {
                    WriteTask writeTask = new WriteTask(goAway, true);
                    // Order the connection failure before waking body readers. Any response
                    // work triggered by cancellation is then rejected behind this command.
                    failConnection(writeTask, connectionError);
                    for (var stream : streamRegistry.applicationStreams()) {
                        stream.cancel(connectionError, false);
                    }
                    writeTask.await(30, TimeUnit.SECONDS);
                    setReadStateAndSignal(HState.ERRORED);
                    writeEndedFuture.get(1, TimeUnit.MINUTES);
                } else {
                    for (var stream : streamRegistry.applicationStreams()) {
                        stream.cancel(connectionError, false);
                    }
                    goAway.writeTo(this, clientOut);
                    clientOut.flush();
                }
            } finally {
                setReadStateAndSignal(HState.ERRORED);
            }

            // todo: raise event, or otherwise mark the connection is handshake failed for the onConnectionEnded listeners
        }

    }

    private void readResetStreamFrame(Http2FrameHeader fh) throws Http2Exception {
        var rstStream = Http2ResetStreamFrame.readFrom(fh, buffer);
        int streamId = rstStream.streamId();
        var stream = streamRegistry.applicationStream(streamId);
        log.info("Reset stream " + rstStream + " for " + stream);
        if (stream == null && (streamId > lastStreamId || streamId % 2 == 0)) {
            throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "Invalid stream ID on rst_stream");
        }

        // The application exchange may already have ended while response DATA remains pending.
        // Reset coordinator state even when there is no longer an application-facing stream.
        if (stream != null) {
            // The coordinator owns the reset, but this reader-owned fence prevents
            // subsequent wire-ordered DATA or trailers from reaching the body first.
            stream.recordPeerResetFromReader();
        }
        resetPendingWritesForStream(rstStream, new IOException("Peer reset stream " + streamId), stream);
    }


    private void readPriorityFrame(Http2FrameHeader fh) throws Http2Exception {
        int payloadLength = fh.length();
        if (payloadLength != 5) {
            buffer.position(buffer.position() + payloadLength);
            throw Http2Exception.stream(Http2ErrorCode.FRAME_SIZE_ERROR, "PRIORITY frame payload must be 5 bytes", fh.streamId());
        }
        int streamDependency = buffer.getInt() & 0x7FFFFFFF;
        buffer.get();
        if (streamDependency == fh.streamId()) {
            throw Http2Exception.stream(Http2ErrorCode.PROTOCOL_ERROR, "PRIORITY stream cannot depend on itself", fh.streamId());
        }
    }

    private void readGoAwayFrame(Http2FrameHeader fh) throws Http2Exception {
        var goaway = Http2GoAway.readFrom(fh, buffer);
        log.info("Got goaway from client " + Objects.requireNonNullElse(goaway.errorCodeEnum(), goaway.errorCode()) + " with last stream " + goaway.lastStreamId());
        stateLock.lock();
        try {
            if (goaway.lastStreamId() > peerGoAwayLastStreamId) {
                throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "GOAWAY last stream ID cannot increase");
            }
            peerGoAwayLastStreamId = goaway.lastStreamId();
            markPeerShutdownInitiatedLocked();
        } finally {
            stateLock.unlock();
        }
        signalWriteLoop();
    }

    private void readWindowUpdate(Http2FrameHeader fh) throws Http2Exception {
        var windowUpdate = Http2WindowUpdate.readFrom(fh, buffer);
        if (windowUpdate.level() == Http2Level.CONNECTION) {
            applyConnectionWindowUpdate(windowUpdate.windowSizeIncrement());
        } else {
            if (windowUpdate.streamId() > lastStreamId || (windowUpdate.streamId() % 2) == 0) {
                throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "Invalid stream ID on window update");
            }
            // The handler may have completed while flow-controlled response DATA remains queued.
            // The coordinator keeps that protocol stream's credit until its writes are drained.
            applyStreamWindowUpdate(windowUpdate.streamId(), windowUpdate.windowSizeIncrement());
        }
    }

    private void readSettingsFrame(Http2FrameHeader fh) throws Http2Exception {
        var settingsDiff = Http2Settings.readFrom(fh, buffer);
        if (settingsDiff.isAck) {
            var ackedOne = settingsAckQueue.poll();
            if (ackedOne == null) {
                throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "Settings ack without pending settings");
            } else if (ackedOne.acknowledge()) {
                log.info("Settings acked");
            }
        } else {
            var oldSettings = clientSettings;
            var newSettings = settingsDiff.copyIfChanged(clientSettings);
            // copyIfChanged returns the input instance when no setting changed.
            @SuppressWarnings("ReferenceEquality")
            boolean settingsChanged = newSettings != oldSettings;
            if (settingsChanged) {
                clientSettings = newSettings;
            }

            // RFC 9113 Section 6.5.3 says an ACK confirms application, not only
            // receipt. The coordinator therefore applies outbound flow credit
            // and HPACK encoder limits before making the ACK writable.
            applyPeerSettingsChange(oldSettings, newSettings);
        }
    }

     private void readPingFrame(Http2FrameHeader fh) throws Http2Exception {
         var ping = Http2Ping.readFrom(fh, buffer);
         if (!ping.isAck()) {
             write(new Http2Ping(true, ping.opaqueData()));
         }
     }

    private void readDataFrame(Http2FrameHeader fh) throws Http2Exception {
        var dataFrame = Http2DataFrame.readFrom(fh, buffer);
        int streamId = dataFrame.streamId();
        Http2StreamRegistry.Lookup registered = streamRegistry.lookup(streamId);
        boolean rejectedBody = registered.rejectedRequestBody();
        var stream = registered.applicationStream();
        if (streamId == 0 || (streamId % 2) == 0
            || (stream == null && !rejectedBody && streamId > lastStreamId)) {
            throw Http2Exception.connection(
                Http2ErrorCode.PROTOCOL_ERROR,
                "Invalid stream ID on data frame"
            );
        }

        // Use the frame length rather than payload length because padding is
        // flow-controlled even though it is discarded while decoding.
        reserveInboundCredit(streamId, fh.length());

        if (rejectedBody) {
            returnInboundCredit(
                streamId,
                fh.length(),
                !dataFrame.endStream()
            );
            if (dataFrame.endStream()) {
                finishRejectedRequestBody(streamId);
            }
            return;
        }

        if (stream == null) {
            // The inbound flow controller normally reports this before returning from
            // reserveInboundCredit. Retain the reader-side guard for a stream
            // retired from the published application map immediately after.
            returnInboundCredit(streamId, fh.length(), false);
            throw new Http2Exception(
                Http2ErrorCode.STREAM_CLOSED,
                "Received data on closed stream",
                streamId
            );
        } else {
            if (stream.peerResetWasRead() || !stream.canReceiveData()) {
                returnInboundCredit(streamId, fh.length(), false);
                throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED, "Received data on closed stream", fh.streamId());
            }
            try {
                stream.onData(fh.length(), dataFrame);
            } catch (Http2Exception e) {
                // The stream will be reset, so only connection credit remains
                // reusable after this invalid DATA frame is discarded.
                returnInboundCredit(streamId, fh.length(), false);
                throw e;
            }
        }
    }

    private boolean noProtocolStreamsAreActive() {
        return !streamRegistry.hasActiveProtocolStreams();
    }

    private void readHeaders(InputStream clientIn, Http2FrameHeader fh, FieldBlockDecoder fieldBlockDecoder) throws Http2Exception, IOException {
        if (fh.streamId() == 0 || (fh.streamId() % 2) == 0) {
            throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "Invalid stream ID " + fh.streamId());
        }
        try {
            var headerFragment = Http2HeadersFrame.readLogicalFrame(fh, fieldBlockDecoder, buffer, clientIn);
            log.info("Got headers " + headerFragment);
            Http2StreamRegistry.Lookup registered =
                streamRegistry.lookup(headerFragment.streamId());
            if (registered.rejectedRequestBody()) {
                if (!headerFragment.endStream()) {
                    throw new Http2Exception(
                        Http2ErrorCode.PROTOCOL_ERROR,
                        "Trailing headers on a rejected request must end the stream",
                        headerFragment.streamId()
                    );
                }
                finishRejectedRequestBody(headerFragment.streamId());
                return;
            }
            var existing = registered.applicationStream();
            if (existing != null) {
                if (existing.peerResetWasRead()) {
                    throw new Http2Exception(
                        Http2ErrorCode.STREAM_CLOSED,
                        "Received headers after RST_STREAM",
                        headerFragment.streamId()
                    );
                }
                // Keep the established behavior for a reused historical stream ID even
                // while its closed protocol record is briefly retained for application
                // cleanup. A still-half-closed-local stream can continue below and accept
                // valid trailers.
                if (existing.protocolStateClosed()) {
                    throw Http2Exception.connection(
                        Http2ErrorCode.PROTOCOL_ERROR,
                        "Invalid stream ID " + headerFragment.streamId()
                    );
                }
                existing.onTrailers(headerFragment);
                return;
            }
            if (fh.streamId() <= lastStreamId) {
                throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "Invalid stream ID " + fh.streamId());
            }
            if (acceptNewStream(headerFragment.streamId())) {
                startRequest(headerFragment);
            }
        } catch (HttpException e) {
            // return an http response
            // The header block could not be decoded (for example a 431 rejected during HPACK
            // decoding), so the method and target are not available here.
            String rejectReason = e.getMessage() != null ? e.getMessage() : e.status().toString();
            Http2StreamRegistry.Lookup registered = streamRegistry.lookup(fh.streamId());
            if (registered.rejectedRequestBody()) {
                if ((fh.flags() & 0b00000001) == 0) {
                    throw new Http2Exception(
                        Http2ErrorCode.PROTOCOL_ERROR,
                        "Trailing headers on a rejected request must end the stream",
                        fh.streamId()
                    );
                }
                finishRejectedRequestBody(fh.streamId());
                return;
            }
            var existing = registered.applicationStream();
            if (existing != null) {
                if (existing.peerResetWasRead()) {
                    throw new Http2Exception(
                        Http2ErrorCode.STREAM_CLOSED,
                        "Received headers after RST_STREAM",
                        fh.streamId()
                    );
                }
                if (!existing.protocolStateClosed()) {
                    // This is a rejected trailer section on an established request.
                    // Reopening the stream would replace its coordinator state and detach
                    // the application exchange. RFC 9113 8.1.1 permits resetting a
                    // malformed request without first sending an HTTP error response.
                    throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, rejectReason, fh.streamId());
                }
            }
            if (fh.streamId() <= lastStreamId) {
                throw Http2Exception.connection(
                    Http2ErrorCode.PROTOCOL_ERROR,
                    "Invalid stream ID " + fh.streamId()
                );
            }
            if (!acceptNewStream(fh.streamId())) {
                return;
            }
            var rejectedRequest =
                new RejectedRequestImpl(e.status().code(), rejectReason, null, null, this);
            FieldBlock errorHeaders = new FieldBlock();
            errorHeaders.add(HeaderNames.PSEUDO_STATUS, Integer.toString(e.status().code()));
            errorHeaders.add(e.responseHeaders());
            byte[] message = rejectReason.getBytes(StandardCharsets.UTF_8);
            errorHeaders.set(HeaderNames.CONTENT_TYPE, "text/plain;charset=utf-8");
            errorHeaders.set(HeaderNames.CONTENT_LENGTH, message.length);
            server.getStatsImpl().onInvalidRequest();
            Http2StreamState initialState = (fh.flags() & 0b00000001) == 0
                ? Http2StreamState.OPEN
                : Http2StreamState.HALF_CLOSED_REMOTE;
            queueRejectedResponse(
                fh.streamId(),
                clientSettings.initialWindowSize,
                initialState,
                new Http2HeadersFrame(fh.streamId(), false, errorHeaders),
                new Http2DataFrame(fh.streamId(), true, message, 0, message.length),
                null
            );
            server.onRequestRejected(rejectedRequest);
        }
    }

    private boolean acceptNewStream(int streamId) {
        stateLock.lock();
        try {
            long now = System.nanoTime();
            if (!canStartNewStreamsLocked(now)) {
                log.info("Refusing stream {} because graceful shutdown no longer allows new streams", streamId);
                writeLocked(new Http2ResetStreamFrame(streamId, Http2ErrorCode.REFUSED_STREAM.code()));
                return false;
            }
            long activeStreams = streamRegistry.concurrentStreamCount();
            if (activeStreams >= serverSettings.maxConcurrentStreams) {
                log.info("Max concurrent streams reached");
                writeLocked(new Http2ResetStreamFrame(streamId, Http2ErrorCode.REFUSED_STREAM.code()));
                return false;
            }
            // An HTTP error response also means the stream was processed. Record every
            // admitted stream before exposing it to application callbacks or queuing
            // flow-controlled output so subsequent frames and GOAWAY share one boundary.
            log.info("Setting last stream id to " + streamId);
            lastStreamId = streamId;
            return true;
        } finally {
            stateLock.unlock();
        }
    }

    private Future<?> startWriteLoop(OutputStream clientOut) {
        writerOutput = clientOut;
        requestWriteRun();
        return writeLoopEnded;
    }

    private void requestWriteRun() {
        if (writerOutput == null || writeLoopEnded.isDone()
            || !writerTaskScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            writerExecutor.execute(this::runWriteTask);
        } catch (RejectedExecutionException e) {
            writerTaskScheduled.set(false);
            failWriteLoop(new IOException("HTTP/2 writer executor rejected connection work", e));
        }
    }

    private void runWriteTask() {
        try {
            OutputStream clientOut = Objects.requireNonNull(writerOutput, "HTTP/2 writer output is not initialized");
            while (writeState.canSendFrames) {
                writeCoordinator.processAvailableCommands();
                if (drainWritableFrames(clientOut)) {
                    continue;
                }

                long now = System.nanoTime();
                boolean continueWriting = false;
                stateLock.lock();
                try {
                    if (shouldQueueFinalGoAwayLocked(now)) {
                        queueFinalGoAwayLocked();
                        continueWriting = true;
                    } else if (isTerminalAndDrainedLocked()) {
                        completeShutdownLocked();
                    } else {
                        long waitTime = nanosUntilNextWriteActionLocked(now);
                        if (waitTime > 0L && !finalGoAwayWakeScheduled) {
                            scheduleFinalGoAwayWakeLocked(waitTime);
                        }
                    }
                } finally {
                    stateLock.unlock();
                }
                if (!continueWriting) {
                    break;
                }
            }
            if (!writeState.canSendFrames) {
                finishWriteLoop(new IOException("HTTP/2 connection write loop closed"));
            }
        } catch (Exception e) {
            log.info("Write loop IO Exception with state=" + writeState);
            failWriteLoop(e);
        } finally {
            writerTaskScheduled.set(false);
            if (!writeLoopEnded.isDone()
                && (writeCoordinator.hasCommands() || !writeState.canSendFrames)) {
                requestWriteRun();
            }
        }
    }

    private void scheduleFinalGoAwayWakeLocked(long delayNanos) {
        finalGoAwayWakeScheduled = true;
        try {
            server.scheduleTimerCallback(() -> {
                boolean wakeWriter;
                stateLock.lock();
                try {
                    finalGoAwayWakeScheduled = false;
                    wakeWriter = writeState.canSendFrames;
                } finally {
                    stateLock.unlock();
                }
                if (wakeWriter) {
                    signalWriteLoop();
                }
            }, delayNanos, TimeUnit.NANOSECONDS);
        } catch (RuntimeException | Error e) {
            finalGoAwayWakeScheduled = false;
            throw e;
        }
    }

    private void failWriteLoop(Exception reason) {
        stateLock.lock();
        try {
            markConnectionErroredLocked();
        } finally {
            stateLock.unlock();
        }
        finishWriteLoop(reason);
    }

    private void finishWriteLoop(Exception reason) {
        if (writeLoopEnded.isDone()) {
            return;
        }
        cancelPendingSettingsAckTimeouts();
        writeCoordinator.failAll(reason);
        closeSocketQuietly();
        // Don't close the output stream here because that closes the TLS connection in Java.
        log.info("Connection write loop closing with state=" + writeState);
        writeLoopEnded.complete(null);
    }

    private void startRequest(Http2HeadersFrame frame) throws Http2Exception {
        var stream = Http2Stream.start(this, frame);
        boolean registered;
        stateLock.lock();
        try {
            registered = readState.canSendFrames && writeState.canSendFrames;
            if (registered) {
                Http2StreamState initialState = frame.endStream()
                    ? Http2StreamState.HALF_CLOSED_REMOTE
                    : Http2StreamState.OPEN;
                inboundFlowControl.openStream(
                    frame.streamId(),
                    serverSettings.initialWindowSize
                );
                writeCoordinator.openStream(
                    frame.streamId(),
                    clientSettings.initialWindowSize,
                    initialState,
                    stream
                );
                streamRegistry.registerApplicationStream(stream);
            }
        } finally {
            stateLock.unlock();
        }
        if (!registered) {
            stream.cancel(new IOException("HTTP/2 connection closed before request could start"), false);
            return;
        }

        onRequestStarted(stream.request);
        try {
            handlerExecutor.submit(server.handlerApplicationTask(() -> startHandledStream(stream)));
        } catch (RejectedExecutionException e) {
            server.onRequestSubmissionRejected(stream.request);
            rejectRequestDueToHandlerOverload(frame, stream);
        }
    }

    private void startHandledStream(Http2Stream stream) {
        try {
            CompletableFuture<@Nullable Void> asyncCompletion = handleExchange(stream.request, stream.response());
            if (asyncCompletion == null) {
                finishHandledStream(stream, null, false);
            } else {
                asyncCompletion.whenComplete((ignored, failure) ->
                    scheduleAsyncStreamCompletion(stream, failure)
                );
            }
        } catch (Throwable failure) {
            finishHandledStream(stream, failure, false);
        }
    }

    private void scheduleAsyncStreamCompletion(Http2Stream stream, @Nullable Throwable failure) {
        Runnable completionTask = () -> finishHandledStream(
            stream,
            failure == null ? null : completionCause(failure),
            true
        );
        RejectedExecutionException rejected = server.tryExecuteHandlerTask(completionTask);
        if (rejected != null) {
            abortAsyncStreamAfterDispatchFailure(stream, rejected);
        }
    }

    private void abortAsyncStreamAfterDispatchFailure(
        Http2Stream stream,
        RejectedExecutionException dispatchFailure
    ) {
        log.warn("Aborting accepted HTTP/2 stream because its application executors rejected completion", dispatchFailure);
        try {
            BaseResponse response = stream.response();
            if (!stream.resetWasInitiated() && !response.responseState().endState()) {
                response.setState(ResponseState.ERRORED);
                write(new Http2ResetStreamFrame(stream.id, Http2ErrorCode.INTERNAL_ERROR.code()));
                stream.cancel(
                    new IOException("Application executors rejected HTTP/2 stream completion", dispatchFailure)
                );
            }
            stream.abandonApplicationExchange();
        } finally {
            onExchangeEnded(stream);
        }
    }

    private void finishHandledStream(Http2Stream stream, @Nullable Throwable failure,
                                     boolean handleAsyncFailure) {
        try {
            if (failure != null) {
                if (handleAsyncFailure && failure instanceof Exception) {
                    handleExchangeException(stream.request, stream.response(), (Exception) failure);
                } else {
                    throw failure;
                }
            }
            stream.cleanup();
        } catch (Throwable e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.info("Unhandled stream exception", e);
            if (stream.response().hasStartedSendingData()
                && !stream.resetWasInitiated()
                && !stream.response().responseState().endState()) {
                stream.response().setState(ResponseState.ERRORED);
                Http2ErrorCode errorCode =
                    e instanceof HttpException
                        && ((HttpException) e).status().sameCode(HttpStatus.REQUEST_TIMEOUT_408)
                        ? Http2ErrorCode.CANCEL
                        : Http2ErrorCode.INTERNAL_ERROR;
                write(new Http2ResetStreamFrame(stream.id, errorCode.code()));
                stream.cancel(new IOException("Unhandled stream exception", e));
            }
        } finally {
            onExchangeEnded(stream);
        }
    }

    private static Throwable completionCause(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
            && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private void rejectRequestDueToHandlerOverload(Http2HeadersFrame frame, Http2Stream stream) {
        rejectedDueToOverload.incrementAndGet();
        server.getStatsImpl().onRejectedDueToOverload();
        stream.cancel(new IOException("Request handler executor rejected the HTTP/2 stream"), false);

        String rejectionReason = "503 Service Unavailable";
        byte[] message = rejectionReason.getBytes(StandardCharsets.UTF_8);
        FieldBlock headers = FieldBlock.newWithDate();
        headers.add(0, new FieldLine(
            HeaderNames.PSEUDO_STATUS,
            HeaderString.valueOf(
                Integer.toString(HttpStatus.SERVICE_UNAVAILABLE_503.code()),
                HeaderString.Type.VALUE
            )
        ));
        headers.set(HeaderNames.CONTENT_TYPE, "text/plain;charset=utf-8");
        headers.set(HeaderNames.CONTENT_LENGTH, message.length);
        Http2StreamState initialState = frame.endStream()
            ? Http2StreamState.HALF_CLOSED_REMOTE
            : Http2StreamState.OPEN;
        queueRejectedResponse(
            frame.streamId(),
            clientSettings.initialWindowSize,
            initialState,
            new Http2HeadersFrame(frame.streamId(), false, headers),
            new Http2DataFrame(frame.streamId(), true, message, 0, message.length),
            stream
        );
        // Queue the response before invoking application code so the independent writer can
        // make progress even if a reject listener is slow.
        server.onRequestRejected(new RejectedRequestImpl(
            HttpStatus.SERVICE_UNAVAILABLE_503.code(),
            rejectionReason,
            stream.request.method().name(),
            stream.request.uri().toString(),
            this
        ));
    }

    private void discardPayload(ByteBuffer buffer, InputStream clientIn, int len) throws IOException {
        while (len > 0) {
            // first ignore stuff already in the buffer
            if (buffer.hasRemaining()) {
                if (len >= buffer.remaining()) {
                    // reset the buffer completely
                    len -= buffer.remaining();
                    buffer.clear().flip();
                } else {
                    buffer.position(buffer.position() + len);
                    len = 0;
                }
            }
            if (len > 0) {
                while (len > buffer.capacity()) {
                    Mutils.readAtLeast(buffer, clientIn, buffer.capacity());
                    buffer.clear();
                    len -= buffer.capacity();
                }
                if (len > 0) {
                    Mutils.readAtLeast(buffer, clientIn, len);
                    buffer.flip();
                    len = 0;
                }
            }
        }
    }


    @Override
    public void abortWithTimeout() {
        // TODO do something with this
        abort();
    }

    @Override
    void initiateGracefulShutdown() {
        stateLock.lock();
        try {
            markLocalShutdownInitiatedLocked();
        } finally {
            stateLock.unlock();
        }
    }

    @Override
    void forceShutdown() {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                markConnectionErroredLocked();
            }
        } finally {
            stateLock.unlock();
        }
        signalWriteLoop();
        closeSocketQuietly();
    }

    @Override
    public HttpVersion httpVersion() {
        return HttpVersion.HTTP_2;
    }

    @Override
    public Set<MuRequest> activeRequests() {
        return streamRegistry.applicationStreams().stream()
            .filter(stream -> !stream.applicationExchangeEnded())
            .map(stream -> stream.request)
            .collect(Collectors.toSet());
    }

    @Override
    public Set<MuWebSocket> activeWebsockets() {
        return Collections.emptySet();
    }

    @Override
    public void abort() {
        forceShutdown();
    }

    @Override
    protected void onExchangeEnded(ResponseInfo exchange) {
        onExchangeEnded((Http2Stream) exchange);
    }

    private void onExchangeEnded(Http2Stream stream) {
        stream.onApplicationExchangeEnded();
        applicationExchangeEndedForWrites(stream.id);
        signalWriteLoop();
        recordExchangeEnded(stream);
        server.executeResponseCompletionTask(() -> notifyExchangeEnded(stream));
    }

    void removeProtocolStream(Http2Stream stream) {
        inboundFlowControl.closeStream(stream.id);
        streamRegistry.removeApplicationStream(stream);
    }
}

interface Http2Peer {
    int maxFrameSize();
    FieldBlockEncoder fieldBlockEncoder();
}
