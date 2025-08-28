package io.muserver;

import org.jspecify.annotations.NonNull;
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
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

class Http2Connection extends BaseHttpConnection implements Http2Peer, CreditAvailableListener {
    private static final int MAX_POSSIBLE_STREAM_ID = 0x7fffffff;
    private static final WriteTask GO_AWAY_WARNING = new WriteTask(new Http2GoAway(MAX_POSSIBLE_STREAM_ID, Http2ErrorCode.NO_ERROR.code(), null), false);

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
    private volatile HState readState =  HState.ACTIVE;
    private volatile HState writeState =  HState.ACTIVE;

    private static final Logger log = LoggerFactory.getLogger(Http2Connection.class);

    private final Http2Settings serverSettings;
    private Http2Settings clientSettings = Http2Settings.DEFAULT_CLIENT_SETTINGS;
    private final ByteBuffer buffer;
    private volatile int maxAllowedStreamId = MAX_POSSIBLE_STREAM_ID;
    private volatile int lastStreamId = 0;
    private final Http2IncomingFlowController incomingFlowControl = new Http2IncomingFlowController(0, 65535);
    private final Http2OutgoingFlowController outgoingFlowControl = new Http2OutgoingFlowController(0, 65535);
    private final ConcurrentLinkedQueue<Long> settingsAckQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Integer, Http2Stream> streams = new ConcurrentHashMap<>();
    private final ExecutorService executorService;

    private final Lock writeQueueLock = new ReentrantLock();
    private final Condition writeQueueCondition = writeQueueLock.newCondition();
    private final Queue<WriteTask> writeQueue = new ArrayDeque<>();

    final FieldBlockEncoder fieldBlockEncoder;

    Http2Connection(Mu3ServerImpl server, ConnectionAcceptor creator, Socket clientSocket, @Nullable Certificate clientCertificate, Instant handshakeStartTime, Http2Settings initialServerSettings, ExecutorService executorService) {
        super(server, creator, clientSocket, clientCertificate, handshakeStartTime);
        this.serverSettings = initialServerSettings;
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
        var update = incomingFlowControl.incrementCredit(credit);
        if (update > 0) {
            write(new Http2WindowUpdate(0, credit));
        }
    }

    void write(LogicalHttp2Frame frame) {
        write(new WriteTask(frame, false));
    }
    void write(WriteTask writeTask) {
        writeQueueLock.lock();
        try {
            writeQueue.add(writeTask);
            writeQueueCondition.signalAll();
        } finally {
            writeQueueLock.unlock();
        }
    }

    @Override
    public void start(InputStream clientIn, OutputStream clientOut) throws Http2Exception, IOException, ExecutionException, InterruptedException, TimeoutException {
        Future<?> writeEndedFuture = null;
        try {
            // do the handshake
            clientSettings = Http2Handshaker.handshake(this, serverSettings, clientSettings, buffer, clientIn, clientOut);

            fieldBlockEncoder.changeTableSize(clientSettings.headerTableSize);
            settingsAckQueue.add(System.currentTimeMillis());

            var fieldBlockDecoder = new FieldBlockDecoder(new HpackTable(serverSettings.headerTableSize), server.maxUrlSize(), server.maxRequestHeadersSize());
            writeEndedFuture = startWriteLoop(clientOut);

            // and now just read frames
            while (readState.canSendFrames) {
                try {
                    Mutils.readAtLeast(buffer, clientIn, Http2FrameHeader.FRAME_HEADER_LENGTH);

                    var fh = Http2FrameHeader.readFrom(buffer);
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
                            case CONTINUATION: {
                                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Out of order continuation frame");
                            }
                            case PUSH_PROMISE: {
                                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Client sent push promise");
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
                    write(new Http2ResetStreamFrame(h2e.streamId(), h2e.errorCode().code()));
                    var stream = streams.get(h2e.streamId());
                    if (stream != null) {
                        stream.cancel(new IOException("Stream error", h2e));
                    }
                } catch (EOFException | SocketException e) {
                    log.warn(e.getClass() + " reading frame at read state " + readState + " writeState=" + writeState, e);
                    // todo: read streams in a lock
                    if (streams.isEmpty()) {
                        readState = HState.COMPLETED; // todo: is this okay or an error?
                    } else {
                        readState = HState.ERRORED;
                    }
                }
                // TODO: end if pending settings ack not received
            }

            writeEndedFuture.get();
            log.info("write loop ended");

        } catch (Http2Exception h2e) {
            log.debug("HTTP2 error", h2e);

            try {
                Http2GoAway goAway = new Http2GoAway(lastStreamId, h2e.errorCode().code(), null);
                if (writeEndedFuture != null) {
                    WriteTask writeTask = new WriteTask(goAway, true);
                    write(writeTask);
                    writeTask.await(30, TimeUnit.SECONDS);
                    writeEndedFuture.get(1, TimeUnit.MINUTES);
                } else {
                    goAway.writeTo(this, clientOut);
                    clientOut.flush();
                }
            } finally {
                readState = HState.ERRORED;

                // TODO: check threadsafety of streams
                if (!streams.isEmpty()) {
                    var connectionError = new IOException("Connection error", h2e);
                    for (var stream : streams.values()) {
                        stream.cancel(connectionError);
                    }
                }
            }

            // todo: raise event, or otherwise mark the connection is handshake failed for the onConnectionEnded listeners
        }

    }

    private void readResetStreamFrame(Http2FrameHeader fh) throws Http2Exception {
        var rstStream = Http2ResetStreamFrame.readFrom(fh, buffer);
        int streamId = rstStream.streamId();
        var stream = streams.get(streamId);
        log.info("Reset stream " + rstStream + " for " + stream);
        if (stream != null) {
            stream.onReset(rstStream);
        } else {
            if (streamId > lastStreamId || streamId % 2 == 0) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Invalid stream ID on rst_stream");
            }
        }
    }

    private void readGoAwayFrame(Http2FrameHeader fh) throws Http2Exception {
        var goaway = Http2GoAway.readFrom(fh, buffer);
        log.info("Got goaway from client " + Objects.requireNonNullElse(goaway.errorCodeEnum(), goaway.errorCode()) + " with last stream " + goaway.lastStreamId());
        writeQueueLock.lock();
        try {
            readState = HState.SHUTDOWN_INITIATED;
            writeQueueCondition.signalAll();
        } finally {
            writeQueueLock.unlock();
        }
    }

    private void readWindowUpdate(Http2FrameHeader fh) throws Http2Exception {
        var windowUpdate = Http2WindowUpdate.readFrom(fh, buffer);
        outgoingFlowControl.applyWindowUpdate(windowUpdate);
        if (windowUpdate.level() == Http2Level.STREAM) {
            Http2Stream stream = streams.get(windowUpdate.streamId());
            if (stream != null) {
                stream.onWindowUpdate(windowUpdate);
            }
        }
    }

    private void readSettingsFrame(Http2FrameHeader fh) throws Http2Exception {
        var settingsDiff = Http2Settings.readFrom(fh, buffer);
        if (settingsDiff.isAck) {
            var ackedOne = settingsAckQueue.poll();
            if (ackedOne == null) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Settings ack without pending settings");
            } else {
                log.info("Settings acked after " + (System.currentTimeMillis() - ackedOne) + "ms");
            }
        } else {
            var oldSettings = clientSettings;
            var newSettings = settingsDiff.copyIfChanged(clientSettings);
            if (newSettings != oldSettings) {
                clientSettings = newSettings;
                if (newSettings.initialWindowSize != oldSettings.initialWindowSize) {
                    for (var stream : streams.values()) {
                        // TODO: pass it
                    }
                }

            }

            // should we write this somewhere else?
            write(Http2Settings.ACK);
        }
    }

    private void readDataFrame(Http2FrameHeader fh) throws Http2Exception {
        var dataFrame = Http2DataFrame.readFrom(fh, buffer);

        // note: checking the length on the header, not the payload length, as padding is discarded when reading data frames
        if (!incomingFlowControl.withdrawIfCan(fh.length())) {
            throw new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR, "Connection flow control credit breach", fh.streamId());
        }

        var stream = streams.get(dataFrame.streamId());
        if (stream == null) {
            // From RFC9113 6.1: If a DATA frame is received whose Stream Identifier field is 0x00, the recipient MUST respond with a connection error
            // From RFC9113 5.1: Receiving any frame other than HEADERS or PRIORITY on a stream in this [idle] state MUST be treated as a connection error
            if (fh.streamId() == 0 || fh.streamId() > lastStreamId || (fh.streamId() % 2) == 0) {
                throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Invalid stream ID on data frame");
            } else {
                // From RFC9113 6.1: If a DATA frame is received whose stream is not in the "open" or "half-closed (local)" state, the recipient MUST respond with a stream error (Section 5.4.2) of type STREAM_CLOSED.
                // As the stream is null, then most likely it is already closed. (Half-closed streams would not be here)
                throw new Http2Exception(Http2ErrorCode.STREAM_CLOSED, "Received data on closed stream", fh.streamId());
            }
        } else {
            stream.onData(fh.length(), dataFrame);
        }
    }

    private void readHeaders(InputStream clientIn, Http2FrameHeader fh, FieldBlockDecoder fieldBlockDecoder) throws Http2Exception, IOException {
        if (fh.streamId() <= lastStreamId || (fh.streamId() % 2) == 0) {
            throw new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "Invalid stream ID " + fh.streamId());
        }
        try {
            var headerFragment = Http2HeadersFrame.readLogicalFrame(fh, fieldBlockDecoder, buffer, clientIn);
            log.info("Got headers " + headerFragment);
            if (activeRequests().size() >= serverSettings.maxConcurrentStreams) {
                log.info("Max concurrent streams reached");
                write(new Http2ResetStreamFrame(headerFragment.streamId(), Http2ErrorCode.REFUSED_STREAM.code()));
            } else {
                log.info("Setting last stream id to " + fh.streamId());
                lastStreamId = fh.streamId();
                startRequest(headerFragment);
            }
        } catch (HttpException e) {
            // return an http response
            FieldBlock errorHeaders = new FieldBlock();
            errorHeaders.add(HeaderNames.PSEUDO_STATUS, e.status());
            errorHeaders.add(e.responseHeaders());
            byte[] message = e.getMessage().getBytes(StandardCharsets.UTF_8);
            if (outgoingFlowControl.credit() >= message.length) {
                errorHeaders.set(HeaderNames.CONTENT_TYPE, "text/plain;charset=utf-8");
                errorHeaders.set(HeaderNames.CONTENT_LENGTH, message.length);
                server.getStatsImpl().onInvalidRequest();
                write(new Http2HeadersFrame(fh.streamId(), false, errorHeaders));
                write(new Http2DataFrame(fh.streamId(), true, message, 0, message.length));
            } else {
                errorHeaders.set(HeaderNames.CONTENT_LENGTH, 0);
                write(new Http2HeadersFrame(fh.streamId(), true, errorHeaders));
            }
        }
    }

    private @NonNull Future<?> startWriteLoop(OutputStream clientOut) {
        var writtenTasks = new ArrayList<WriteTask>(8);
        return executorService.submit(() -> {

            while (writeState.canSendFrames) {
                writeQueueLock.lock();
                try {
                    while (!writeQueue.isEmpty()) {
                        WriteTask candidate = null;
                        for (var task : writeQueue) {
                            if (candidate == null) {
                                var frame = task.frame();
                                int requiredCredit = frame.flowControlSize();
                                log.info("frame=" + frame.getClass().getSimpleName() + " required=" + requiredCredit + " available=" + outgoingFlowControl.credit());
                                if (requiredCredit == 0 || requiredCredit <= outgoingFlowControl.credit()) {
                                    candidate = task;
                                }
                            }
                        }
                        if (candidate != null) {
                            if (outgoingFlowControl.withdrawIfCan(candidate.frame().flowControlSize())) {
                                writeQueue.remove(candidate);
                                log.info("Writing " + candidate.frame());
                                candidate.frame().writeTo(this, clientOut);
                                writtenTasks.add(candidate);
                            }
                        } else {
                            break; // stop draining writeQueue until more items or more credit available
                        }

                        if (!writtenTasks.isEmpty()) {
                            clientOut.flush();
                            for (WriteTask task : writtenTasks) {
                                task.complete();
                            }
                            writtenTasks.clear();
                        }

                    }
                    boolean shutDownInitiatedByEitherSide = readState == HState.SHUTDOWN_INITIATED || writeState == HState.SHUTDOWN_INITIATED;
                    if (shutDownInitiatedByEitherSide && maxAllowedStreamId == MAX_POSSIBLE_STREAM_ID) {
                        maxAllowedStreamId = lastStreamId;
                        log.info("Queuing final go away with last stream id " + maxAllowedStreamId);
                        write(new Http2GoAway(maxAllowedStreamId, 0, null));
                    } else if (shutDownInitiatedByEitherSide && streams.isEmpty()) {
                        writeState = HState.COMPLETED;
                        readState = HState.COMPLETED;
                    } else {
                        writeQueueCondition.await();
                    }
                } catch (Exception e) {
                    log.info("Write loop IO Exception with state=" + writeState);
                    writeState = HState.ERRORED;
                    WriteTask task;
                    while ((task = writeQueue.poll()) != null) {
                        task.fail(e);
                    }
                } finally {
                    writeQueueLock.unlock();
                }
            }
            // note: don't close the output stream here as that closes the TLS connection in java
            log.info("Connection write loop closing with state=" + writeState);
        });
    }

    private void startRequest(Http2HeadersFrame frame) throws Http2Exception {
        var stream = Http2Stream.start(this, frame, serverSettings, clientSettings);
        streams.put(frame.streamId(), stream);
        onRequestStarted(stream.request);
        executorService.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    handleExchange(stream.request, stream.response());
                    stream.cleanup();
                } catch (Throwable e) {
                    log.info("Unhandled stream exception", e);
                } finally {
                    onExchangeEnded(stream);
                }

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
        writeQueueLock.lock();
        try {
            if (writeState == HState.ACTIVE) {
                log.info("Graceful shutdown initiated with write state " + writeState);
                writeState = HState.SHUTDOWN_INITIATED;
                // As per: https://datatracker.ietf.org/doc/html/rfc9113#section-6.8-18
                // A server that is attempting to gracefully shut down a connection SHOULD send an initial GOAWAY frame
                // with the last stream identifier set to 2^31-1 and a NO_ERROR code. This signals to the client that a
                // shutdown is imminent and that initiating further requests is prohibited. After allowing time for any
                // in-flight stream creation (at least one round-trip time), the server MAY send another GOAWAY frame
                // with an updated last stream identifier. This ensures that a connection can be cleanly shut down
                // without losing requests.
                write(GO_AWAY_WARNING);
            }
        } finally {
            writeQueueLock.unlock();
        }
    }

    @Override
    void forceShutdown() {
        writeQueueLock.lock();
        try {
            if (writeState.canSendFrames) {
                writeState = HState.ERRORED;
                writeQueueCondition.signalAll();
            }
        } finally {
            writeQueueLock.unlock();
        }
    }

    @Override
    public HttpVersion httpVersion() {
        return HttpVersion.HTTP_2;
    }

    @Override
    public Set<MuRequest> activeRequests() {
        return streams.values().stream().map(s -> s.request).collect(Collectors.toSet());
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
        streams.remove(stream.id);
        super.onExchangeEnded(exchange);
    }
}

interface Http2Peer {
    int maxFrameSize();
    FieldBlockEncoder fieldBlockEncoder();
}

