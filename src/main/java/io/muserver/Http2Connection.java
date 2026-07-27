package io.muserver;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

class Http2Connection extends BaseHttpConnection implements Http2Peer, CreditAvailableListener {
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
    private Http2Settings clientSettings = Http2Settings.DEFAULT_CLIENT_SETTINGS;
    private final ByteBuffer buffer;
    private volatile int maxAllowedStreamId = MAX_POSSIBLE_STREAM_ID;
    private volatile int lastStreamId = 0;
    private volatile int peerGoAwayLastStreamId = MAX_POSSIBLE_STREAM_ID;
    private final Http2IncomingFlowController incomingFlowControl = new Http2IncomingFlowController(0, 65535);
    private final ConcurrentLinkedQueue<Long> settingsAckQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Integer, Http2Stream> streams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Http2IncomingFlowController> rejectedRequestBodies = new ConcurrentHashMap<>();
    private final ExecutorService executorService;
    private final long settingsAckTimeoutMillis;

    private final Lock stateLock = new ReentrantLock();
    private final Http2WriteCoordinator writeCoordinator = new Http2WriteCoordinator(65535);
    private volatile boolean finalGoAwayQueued;
    private volatile long finalGoAwayEarliestTime = Long.MAX_VALUE;

    final FieldBlockEncoder fieldBlockEncoder;

    Http2Connection(Mu3ServerImpl server, ConnectionAcceptor creator, Socket clientSocket, @Nullable Certificate clientCertificate, Instant handshakeStartTime, Http2Settings initialServerSettings, long settingsAckTimeoutMillis, ExecutorService executorService) {
        super(server, creator, clientSocket, clientCertificate, handshakeStartTime);
        this.serverSettings = initialServerSettings;
        this.settingsAckTimeoutMillis = settingsAckTimeoutMillis;
        this.executorService = executorService;
        this.buffer = ByteBuffer.allocate(serverSettings.maxFrameSize).flip();
        this.fieldBlockEncoder = new FieldBlockEncoder(new HpackTable(clientSettings.headerTableSize));
    }

    @Override
    public int maxFrameSize() {
        return clientSettings.maxFrameSize;
    }

    @Override
    public FieldBlockEncoder fieldBlockEncoder() {
        return fieldBlockEncoder;
    }

    @Override
    public void creditAvailable(int credit) throws Http2Exception {
        refundDiscardedConnectionCredit(credit);
    }

    private void refundDiscardedConnectionCredit(int credit) throws Http2Exception {
        var update = incomingFlowControl.incrementCredit(credit);
        if (update > 0) {
            write(new Http2WindowUpdate(0, update));
        }
    }

    void write(LogicalHttp2Frame frame) {
        write(new WriteTask(frame, false));
    }

    void write(WriteTask writeTask) {
        stateLock.lock();
        try {
            if (writeState.canSendFrames && canWriteFrame(writeTask.frame())) {
                LogicalHttp2Frame frame = writeTask.frame();
                if (frame instanceof Http2ResetStreamFrame) {
                    rejectedRequestBodies.remove(frame.streamId());
                }
                boolean retainResetState = frame instanceof Http2ResetStreamFrame
                    && streams.containsKey(frame.streamId());
                writeCoordinator.submit(writeTask, retainResetState);
            } else {
                writeTask.fail(new IOException("HTTP/2 connection or stream is closed"));
            }
        } finally {
            stateLock.unlock();
        }
    }

    private boolean canWriteFrame(LogicalHttp2Frame frame) {
        int streamId = frame.streamId();
        if (streamId == 0 || frame instanceof Http2ResetStreamFrame) {
            return true;
        }
        Http2Stream stream = streams.get(streamId);
        return stream == null
            ? streamId > lastStreamId
            : !stream.resetWasInitiated();
    }

    private void writeFirst(LogicalHttp2Frame frame) {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                writeCoordinator.submitFirst(new WriteTask(frame, false));
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void signalWriteLoop() {
        stateLock.lock();
        try {
            writeCoordinator.wakeUp();
        } finally {
            stateLock.unlock();
        }
    }

    private void resetPendingWritesForStream(
        Http2ResetStreamFrame resetFrame,
        IOException reason,
        @Nullable Http2Stream stream
    ) throws Http2Exception {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                rejectedRequestBodies.remove(resetFrame.streamId());
                writeCoordinator.resetStream(resetFrame, reason, stream);
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
        Http2DataFrame body
    ) {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                // Rejected headers do not create an application-facing Http2Stream, so queue
                // their complete protocol exchange directly as one ordered coordinator transaction.
                writeCoordinator.openStream(streamId, initialCredit, initialState, null);
                writeCoordinator.submit(new WriteTask(headers, false));
                writeCoordinator.submit(new WriteTask(body, false));
                if (initialState.canReceiveEndStream()) {
                    rejectedRequestBodies.put(
                        streamId,
                        new Http2IncomingFlowController(streamId, serverSettings.initialWindowSize)
                    );
                } else {
                    writeCoordinator.forgetStream(streamId);
                }
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void queueRejectedStreamWindowUpdate(int streamId, int increment) {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                writeCoordinator.submit(new WriteTask(new Http2WindowUpdate(streamId, increment), false));
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void finishRejectedRequestBody(int streamId) throws Http2Exception {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                rejectedRequestBodies.remove(streamId);
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

    private void applyInitialWindowSizeChange(int difference) throws Http2Exception {
        stateLock.lock();
        try {
            if (writeState.canSendFrames) {
                writeCoordinator.applyInitialWindowSizeChange(
                    difference,
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

    private void queuePendingSettingsAck() {
        settingsAckQueue.add(System.currentTimeMillis() + settingsAckTimeoutMillis);
    }

    private void prepareForFrameRead() throws SocketException, Http2Exception {
        Long deadline = settingsAckQueue.peek();
        if (deadline == null) {
            clientSocket.setSoTimeout(0);
            return;
        }
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
            throw Http2Exception.connection(Http2ErrorCode.SETTINGS_TIMEOUT, "Timed out waiting for SETTINGS ack");
        }
        clientSocket.setSoTimeout((int) Math.min(Integer.MAX_VALUE, remaining));
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
        for (Http2Stream stream : streams.values()) {
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
            finalGoAwayEarliestTime = System.currentTimeMillis() + GO_AWAY_GRACE_PERIOD_MILLIS;
            // As per: https://datatracker.ietf.org/doc/html/rfc9113#section-6.8-18
            // A server that is attempting to gracefully shut down a connection SHOULD send an initial GOAWAY frame
            // with the last stream identifier set to 2^31-1 and a NO_ERROR code. This signals to the client that a
            // shutdown is imminent and that initiating further requests is prohibited. After allowing time for any
            // in-flight stream creation (at least one round-trip time), the server MAY send another GOAWAY frame
            // with an updated last stream identifier. This ensures that a connection can be cleanly shut down
            // without losing requests.
            write(GO_AWAY_WARNING);
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

    private long localGoAwayGracePeriodMillisRemainingLocked(long now) {
        return Math.max(0L, finalGoAwayEarliestTime - now);
    }

    private boolean isStillAllowingInFlightStreamCreationLocked(long now) {
        return isLocalShutdownInitiatedLocked()
            && localGoAwayGracePeriodMillisRemainingLocked(now) > 0L;
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
            return localGoAwayGracePeriodMillisRemainingLocked(now) == 0L;
        }
        return isPeerShutdownInitiatedLocked()
            && streams.isEmpty()
            && rejectedRequestBodies.isEmpty();
    }

    private void queueFinalGoAwayLocked() {
        finalGoAwayQueued = true;
        maxAllowedStreamId = lastStreamId;
        log.info("Queuing final go away with last stream id {}", maxAllowedStreamId);
        write(new Http2GoAway(maxAllowedStreamId, 0, null));
    }

    private boolean isTerminalAndDrainedLocked() {
        return (isReadTerminalLocked() || (isShutdownInitiatedLocked() && finalGoAwayQueued))
            && streams.isEmpty()
            && rejectedRequestBodies.isEmpty()
            && writeCoordinator.isIdle();
    }

    private long millisUntilNextWriteActionLocked(long now) {
        if (isLocalShutdownInitiatedLocked() && !finalGoAwayQueued) {
            return localGoAwayGracePeriodMillisRemainingLocked(now);
        }
        return -1L;
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
            Http2Stream stream = streams.get(error.streamId());
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
        for (Http2Stream stream : streams.values()) {
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

            fieldBlockEncoder.changeTableSize(clientSettings.headerTableSize);
            queuePendingSettingsAck();

            var fieldBlockDecoder = new FieldBlockDecoder(new HpackTable(serverSettings.headerTableSize), server.maxUrlSize(), server.maxRequestHeadersSize());
            writeEndedFuture = startWriteLoop(clientOut);

            // and now just read frames
            while (readState.canSendFrames) {
                Http2FrameHeader currentFrameHeader = null;
                boolean readingFrameHeader = true;
                try {
                    prepareForFrameRead();
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
                    var stream = streams.get(h2e.streamId());
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
                } catch (SocketTimeoutException e) {
                    if (!settingsAckQueue.isEmpty()) {
                        throw Http2Exception.connection(Http2ErrorCode.SETTINGS_TIMEOUT, "Timed out waiting for SETTINGS ack");
                    }
                    throw e;
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
                // TODO: end if pending settings ack not received
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
                    for (var stream : streams.values()) {
                        stream.cancel(connectionError, false);
                    }
                    writeTask.await(30, TimeUnit.SECONDS);
                    setReadStateAndSignal(HState.ERRORED);
                    writeEndedFuture.get(1, TimeUnit.MINUTES);
                } else {
                    for (var stream : streams.values()) {
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
        var stream = streams.get(streamId);
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
            } else {
                log.info("Settings acked");
            }
        } else {
            var oldSettings = clientSettings;
            var newSettings = settingsDiff.copyIfChanged(clientSettings);
            // copyIfChanged returns the input instance when no setting changed.
            @SuppressWarnings("ReferenceEquality")
            boolean settingsChanged = newSettings != oldSettings;
            boolean initialWindowSizeChanged = settingsChanged
                && newSettings.initialWindowSize != oldSettings.initialWindowSize;
            if (settingsChanged) {
                clientSettings = newSettings;
            }

            // The ACK is ordered ahead of DATA that the new initial window size might unblock.
            if (initialWindowSizeChanged) {
                int difference = newSettings.initialWindowSize - oldSettings.initialWindowSize;
                applyInitialWindowSizeChange(difference);
            } else {
                writeFirst(Http2Settings.ACK);
            }
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

        // note: checking the length on the header, not the payload length, as padding is discarded when reading data frames
        if (!incomingFlowControl.withdrawIfCan(fh.length())) {
            throw Http2Exception.connection(Http2ErrorCode.FLOW_CONTROL_ERROR, "Connection flow control credit breach");
        }

        var rejectedBodyFlowControl = rejectedRequestBodies.get(dataFrame.streamId());
        if (rejectedBodyFlowControl != null) {
            if (!rejectedBodyFlowControl.withdrawIfCan(fh.length())) {
                refundDiscardedConnectionCredit(fh.length());
                throw new Http2Exception(
                    Http2ErrorCode.FLOW_CONTROL_ERROR,
                    "Not enough flow control credit for rejected request stream",
                    dataFrame.streamId()
                );
            }

            int streamUpdate = rejectedBodyFlowControl.incrementCredit(fh.length());
            refundDiscardedConnectionCredit(fh.length());
            if (dataFrame.endStream()) {
                finishRejectedRequestBody(dataFrame.streamId());
            } else if (streamUpdate > 0) {
                queueRejectedStreamWindowUpdate(dataFrame.streamId(), streamUpdate);
            }
            return;
        }

        var stream = streams.get(dataFrame.streamId());
        if (stream == null) {
            // From RFC9113 6.1: If a DATA frame is received whose Stream Identifier field is 0x00, the recipient MUST respond with a connection error
            // From RFC9113 5.1: Receiving any frame other than HEADERS or PRIORITY on a stream in this [idle] state MUST be treated as a connection error
            if (fh.streamId() == 0 || fh.streamId() > lastStreamId || (fh.streamId() % 2) == 0) {
                throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "Invalid stream ID on data frame");
            } else {
                // From RFC9113 6.1: If a DATA frame is received whose stream is not in the "open" or "half-closed (local)" state, the recipient MUST respond with a stream error (Section 5.4.2) of type STREAM_CLOSED.
                // As the stream is null, then most likely it is already closed. (Half-closed streams would not be here)
                refundDiscardedConnectionCredit(fh.length());
                throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED, "Received data on closed stream", fh.streamId());
            }
        } else {
            if (stream.peerResetWasRead() || !stream.canReceiveData()) {
                refundDiscardedConnectionCredit(fh.length());
                throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED, "Received data on closed stream", fh.streamId());
            }
            try {
                stream.onData(fh.length(), dataFrame);
            } catch (Http2Exception e) {
                refundDiscardedConnectionCredit(fh.length());
                throw e;
            }
        }
    }

    private boolean noProtocolStreamsAreActive() {
        return rejectedRequestBodies.isEmpty()
            && streams.values().stream().noneMatch(Http2Stream::countsTowardsMaxConcurrentStreams);
    }

    private void readHeaders(InputStream clientIn, Http2FrameHeader fh, FieldBlockDecoder fieldBlockDecoder) throws Http2Exception, IOException {
        if (fh.streamId() == 0 || (fh.streamId() % 2) == 0) {
            throw Http2Exception.connection(Http2ErrorCode.PROTOCOL_ERROR, "Invalid stream ID " + fh.streamId());
        }
        try {
            var headerFragment = Http2HeadersFrame.readLogicalFrame(fh, fieldBlockDecoder, buffer, clientIn);
            log.info("Got headers " + headerFragment);
            if (rejectedRequestBodies.containsKey(headerFragment.streamId())) {
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
            var existing = streams.get(headerFragment.streamId());
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
            if (rejectedRequestBodies.containsKey(fh.streamId())) {
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
            var existing = streams.get(fh.streamId());
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
            server.onRequestRejected(new RejectedRequestImpl(e.status().code(), rejectReason, null, null, this));
            FieldBlock errorHeaders = new FieldBlock();
            errorHeaders.add(HeaderNames.PSEUDO_STATUS, e.status());
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
                new Http2DataFrame(fh.streamId(), true, message, 0, message.length)
            );
        }
    }

    private boolean acceptNewStream(int streamId) {
        stateLock.lock();
        try {
            long now = System.currentTimeMillis();
            if (!canStartNewStreamsLocked(now)) {
                log.info("Refusing stream {} because graceful shutdown no longer allows new streams", streamId);
                write(new Http2ResetStreamFrame(streamId, Http2ErrorCode.REFUSED_STREAM.code()));
                return false;
            }
            long activeStreams = rejectedRequestBodies.size()
                + streams.values().stream()
                    .filter(Http2Stream::countsTowardsMaxConcurrentStreams)
                    .count();
            if (activeStreams >= serverSettings.maxConcurrentStreams) {
                log.info("Max concurrent streams reached");
                write(new Http2ResetStreamFrame(streamId, Http2ErrorCode.REFUSED_STREAM.code()));
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
        return executorService.submit(() -> {
            while (writeState.canSendFrames) {
                try {
                    writeCoordinator.processAvailableCommands();
                    if (drainWritableFrames(clientOut)) {
                        continue;
                    }

                    long now = System.currentTimeMillis();
                    boolean waitForCommand = false;
                    long waitTime = -1L;
                    stateLock.lock();
                    try {
                        if (shouldQueueFinalGoAwayLocked(now)) {
                            queueFinalGoAwayLocked();
                        } else if (isTerminalAndDrainedLocked()) {
                            completeShutdownLocked();
                        } else {
                            waitForCommand = true;
                            waitTime = millisUntilNextWriteActionLocked(now);
                        }
                    } finally {
                        stateLock.unlock();
                    }
                    if (waitForCommand) {
                        writeCoordinator.awaitCommand(waitTime);
                    }
                } catch (Exception e) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.info("Write loop IO Exception with state=" + writeState);
                    stateLock.lock();
                    try {
                        markConnectionErroredLocked();
                    } finally {
                        stateLock.unlock();
                    }
                    writeCoordinator.failAll(e);
                }
            }
            writeCoordinator.failAll(new IOException("HTTP/2 connection write loop closed"));
            closeSocketQuietly();
            // note: don't close the output stream here as that closes the TLS connection in java
            log.info("Connection write loop closing with state=" + writeState);
        });
    }

    private void startRequest(Http2HeadersFrame frame) throws Http2Exception {
        var stream = Http2Stream.start(this, frame, serverSettings, clientSettings);
        boolean registered;
        stateLock.lock();
        try {
            registered = readState.canSendFrames && writeState.canSendFrames;
            if (registered) {
                Http2StreamState initialState = frame.endStream()
                    ? Http2StreamState.HALF_CLOSED_REMOTE
                    : Http2StreamState.OPEN;
                writeCoordinator.openStream(
                    frame.streamId(),
                    clientSettings.initialWindowSize,
                    initialState,
                    stream
                );
                streams.put(frame.streamId(), stream);
            }
        } finally {
            stateLock.unlock();
        }
        if (!registered) {
            stream.cancel(new IOException("HTTP/2 connection closed before request could start"), false);
            return;
        }
        onRequestStarted(stream.request);
        executorService.submit(() -> {
            try {
                handleExchange(stream.request, stream.response());
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
                    write(new Http2ResetStreamFrame(stream.id, Http2ErrorCode.INTERNAL_ERROR.code()));
                    stream.cancel(new IOException("Unhandled stream exception", e), false);
                }
            } finally {
                onExchangeEnded(stream);
            }
        });
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
        return streams.values().stream()
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
        var stream = (Http2Stream) exchange;
        stream.onApplicationExchangeEnded();
        applicationExchangeEndedForWrites(stream.id);
        signalWriteLoop();
        super.onExchangeEnded(exchange);
    }

    void removeProtocolStream(Http2Stream stream) {
        streams.remove(stream.id, stream);
    }
}

interface Http2Peer {
    int maxFrameSize();
    FieldBlockEncoder fieldBlockEncoder();
}
