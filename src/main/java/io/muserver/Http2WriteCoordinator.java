package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The multiple-producer, single-consumer mailbox and pending-write scheduler for an HTTP/2 connection.
 *
 * <p>Application and reader threads only submit commands. Serialized writer drain tasks are the sole
 * callers of the command-processing, scheduling, and failure methods.</p>
 */
final class Http2WriteCoordinator {

    enum ResetRetention { UNTIL_APPLICATION_ENDS, NONE }

    final class WritableFrame {
        private final LogicalHttp2Frame frame;
        private final WriteTask task;
        private final boolean completesTask;
        private final @Nullable Http2Exception protocolError;

        private WritableFrame(
            LogicalHttp2Frame frame,
            WriteTask task,
            boolean completesTask,
            @Nullable Http2Exception protocolError
        ) {
            this.frame = frame;
            this.task = task;
            this.completesTask = completesTask;
            this.protocolError = protocolError;
        }

        LogicalHttp2Frame frame() {
            return frame;
        }

        @Nullable Http2Exception protocolError() {
            return protocolError;
        }

        void publishAfterWrite(LogicalHttp2Frame frameWritten) {
            if (frameWritten.endStream()) {
                Http2Stream stream = applicationStreams.get(frameWritten.streamId());
                if (stream != null) {
                    stream.onLocalEndStreamPublished();
                }
            }
        }

        boolean beginWrite() {
            if (task.beginWrite()) return true;
            // A cancellation can race the credit reservation. No bytes reached the peer.
            if (frame instanceof Http2DataFrame) {
                int reserved = frame.flowControlSize();
                connectionCredit += reserved;
                streamCredits.computeIfPresent(frame.streamId(), (id, credit) -> credit + reserved);
            }
            return false;
        }

        void complete() {
            if (completesTask) onWriteCompleted(frame);
            task.finishPart(completesTask);
        }

        void fail(Exception reason) {
            task.fail(reason);
        }
    }

    private interface Command {
    }

    private static final class QueueWrite implements Command {
        private final WriteTask task;
        private final ResetRetention retainResetState;

        private QueueWrite(WriteTask task, ResetRetention retainResetState) {
            this.task = task;
            this.retainResetState = retainResetState;
        }
    }

    private static final class ResetStream implements Command {
        private final int streamId;
        private final Http2ResetStreamFrame resetFrame;
        private final IOException reason;
        private final @Nullable Http2Stream stream;

        private ResetStream(Http2ResetStreamFrame resetFrame, IOException reason, @Nullable Http2Stream stream) {
            this.streamId = resetFrame.streamId();
            this.resetFrame = resetFrame;
            this.reason = reason;
            this.stream = stream;
        }
    }

    private static final class RemoteEndStream implements Command {
        private final int streamId;

        private RemoteEndStream(int streamId) {
            this.streamId = streamId;
        }
    }

    private static final class OpenStream implements Command {
        private final int streamId;
        private final int initialCredit;
        private final Http2StreamState initialState;
        private final @Nullable Http2Stream stream;

        private OpenStream(
            int streamId,
            int initialCredit,
            Http2StreamState initialState,
            @Nullable Http2Stream stream
        ) {
            this.streamId = streamId;
            this.initialCredit = initialCredit;
            this.initialState = initialState;
            this.stream = stream;
        }
    }

    private static final class ForgetStream implements Command {
        private final int streamId;

        private ForgetStream(int streamId) {
            this.streamId = streamId;
        }
    }

    private static final class ApplicationExchangeEnded implements Command {
        private final int streamId;

        private ApplicationExchangeEnded(int streamId) {
            this.streamId = streamId;
        }
    }

    private static final class ConnectionWindowUpdate implements Command {
        private final int increment;
        private final int lastStreamId;

        private ConnectionWindowUpdate(int increment, int lastStreamId) {
            this.increment = increment;
            this.lastStreamId = lastStreamId;
        }
    }

    private static final class StreamWindowUpdate implements Command {
        private final int streamId;
        private final int increment;

        private StreamWindowUpdate(int streamId, int increment) {
            this.streamId = streamId;
            this.increment = increment;
        }
    }

    private static final class PeerSettingsChange implements Command {
        private final int initialWindowDifference;
        private final int headerTableSize;
        private final WriteTask acknowledgement;
        private final int lastStreamId;

        private PeerSettingsChange(
            int initialWindowDifference,
            int headerTableSize,
            WriteTask acknowledgement,
            int lastStreamId
        ) {
            this.initialWindowDifference = initialWindowDifference;
            this.headerTableSize = headerTableSize;
            this.acknowledgement = acknowledgement;
            this.lastStreamId = lastStreamId;
        }
    }

    private enum SettingsTimeout implements Command {
        INSTANCE
    }

    private static final class ConnectionFailure implements Command {
        private final WriteTask goAway;
        private final IOException reason;

        private ConnectionFailure(WriteTask goAway, IOException reason) {
            this.goAway = goAway;
            this.reason = reason;
        }
    }

    private enum WakeUp implements Command {
        INSTANCE
    }

    private final BlockingQueue<Command> mailbox = new LinkedBlockingQueue<>();
    private final Runnable commandQueued;
    private final ArrayDeque<PendingWrite> pendingWrites = new ArrayDeque<>();
    private final Set<Integer> peerResetStreams = new HashSet<>();
    private final Set<Integer> retainedLocalResetStreams = new HashSet<>();
    private final Set<Integer> localResetsPendingWrite = new HashSet<>();
    private final Set<Integer> streamsPendingRemoval = new HashSet<>();
    private final Map<Integer, Integer> streamCredits = new HashMap<>();
    private final Map<Integer, Http2StreamState> streamStates = new HashMap<>();
    private final Map<Integer, Http2Stream> applicationStreams = new HashMap<>();
    private final ArrayList<Command> commandBatch = new ArrayList<>();
    private final AtomicBoolean wakeUpQueued = new AtomicBoolean();
    private final FieldBlockEncoder fieldBlockEncoder =
        new FieldBlockEncoder(new HpackTable(Http2Settings.DEFAULT_CLIENT_SETTINGS.headerTableSize));
    private int connectionCredit;
    private boolean connectionErrorPendingGoAway;
    private @Nullable IOException connectionFailureReason;

    Http2WriteCoordinator(int initialConnectionCredit) {
        this(initialConnectionCredit, () -> {
        });
    }

    Http2WriteCoordinator(int initialConnectionCredit, Runnable commandQueued) {
        if (initialConnectionCredit < 0) {
            throw new IllegalArgumentException("Initial connection credit cannot be negative");
        }
        this.connectionCredit = initialConnectionCredit;
        this.commandQueued = commandQueued;
    }

    void submit(WriteTask task) {
        submit(task, ResetRetention.UNTIL_APPLICATION_ENDS);
    }

    void submit(WriteTask task, ResetRetention retainResetState) {
        enqueue(new QueueWrite(task, retainResetState));
    }

    void resetStream(Http2ResetStreamFrame resetFrame, IOException reason, @Nullable Http2Stream stream) {
        int streamId = resetFrame.streamId();
        if (streamId <= 0) {
            throw new IllegalArgumentException("A reset stream ID must be positive");
        }
        enqueue(new ResetStream(resetFrame, reason, stream));
    }

    void openStream(int streamId, int initialCredit) {
        openStream(streamId, initialCredit, Http2StreamState.OPEN, null);
    }

    void openStream(
        int streamId,
        int initialCredit,
        Http2StreamState initialState,
        @Nullable Http2Stream stream
    ) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("An open stream ID must be positive");
        }
        if (initialCredit < 0) {
            throw new IllegalArgumentException("Initial stream credit cannot be negative");
        }
        if (initialState == Http2StreamState.CLOSED) {
            throw new IllegalArgumentException("A newly opened stream cannot be closed");
        }
        enqueue(new OpenStream(streamId, initialCredit, initialState, stream));
    }

    void remoteEndStream(int streamId) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("An ended stream ID must be positive");
        }
        enqueue(new RemoteEndStream(streamId));
    }

    void forgetStream(int streamId) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("A forgotten stream ID must be positive");
        }
        enqueue(new ForgetStream(streamId));
    }

    void applicationExchangeEnded(int streamId) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("An ended application exchange stream ID must be positive");
        }
        enqueue(new ApplicationExchangeEnded(streamId));
    }

    void applyConnectionWindowUpdate(int increment, int lastStreamId) {
        if (increment <= 0) {
            throw new IllegalArgumentException("A connection window increment must be positive");
        }
        if (lastStreamId < 0) {
            throw new IllegalArgumentException("The last stream ID cannot be negative");
        }
        enqueue(new ConnectionWindowUpdate(increment, lastStreamId));
    }

    void applyStreamWindowUpdate(int streamId, int increment) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("A stream window update requires a positive stream ID");
        }
        if (increment <= 0) {
            throw new IllegalArgumentException("A stream window increment must be positive");
        }
        enqueue(new StreamWindowUpdate(streamId, increment));
    }

    void initializePeerHeaderTableSize(int headerTableSize) {
        fieldBlockEncoder.changeTableSize(headerTableSize);
    }

    FieldBlockEncoder fieldBlockEncoder() {
        return fieldBlockEncoder;
    }

    void applyPeerSettingsChange(
        int initialWindowDifference,
        int headerTableSize,
        WriteTask acknowledgement,
        int lastStreamId
    ) {
        if (headerTableSize < 0) {
            throw new IllegalArgumentException("The peer header table size cannot be negative");
        }
        if (lastStreamId < 0) {
            throw new IllegalArgumentException("The last stream ID cannot be negative");
        }
        enqueue(new PeerSettingsChange(
            initialWindowDifference,
            headerTableSize,
            acknowledgement,
            lastStreamId
        ));
    }

    void settingsTimedOut() {
        enqueue(SettingsTimeout.INSTANCE);
    }

    void failConnection(WriteTask goAway, IOException reason) {
        if (!(goAway.frame() instanceof Http2GoAway)) {
            throw new IllegalArgumentException("A connection failure must carry GOAWAY");
        }
        enqueue(new ConnectionFailure(goAway, reason));
    }

    void wakeUp() {
        if (wakeUpQueued.compareAndSet(false, true)) {
            enqueue(WakeUp.INSTANCE);
        }
    }

    private void enqueue(Command command) {
        mailbox.add(command);
        commandQueued.run();
    }

    /**
     * Applies a snapshot of commands that are currently available.
     *
     * <p>Commands submitted while this method runs remain in the mailbox for the next pass, preventing
     * a continuously busy producer from starving socket writes.</p>
     */
    void processAvailableCommands() {
        mailbox.drainTo(commandBatch);
        for (Command command : commandBatch) {
            apply(command);
        }
        commandBatch.clear();
    }

    /**
     * Returns and removes the first pending write that can reserve its flow-control credit.
     *
     * <p>A blocked frame prevents later frames for the same stream from overtaking it. Frames for other
     * streams and connection-level frames remain eligible, so one flow-controlled stream cannot block
     * the connection. A stream-level WINDOW_UPDATE is also eligible because it controls the independent
     * inbound flow-control direction and delaying it behind outbound DATA can deadlock a bidirectional
     * exchange.</p>
     */
    @Nullable WritableFrame pollWritable() {
        Set<Integer> blockedStreams = null;
        for (Iterator<PendingWrite> iterator = pendingWrites.iterator(); iterator.hasNext(); ) {
            PendingWrite pending = iterator.next();
            WriteTask task = pending.task;
            if (task.isCancelled()) { iterator.remove(); continue; }
            int streamId = task.frame().streamId();
            LogicalHttp2Frame frame = task.frame();
            if (connectionErrorPendingGoAway && !(frame instanceof Http2GoAway)) {
                continue;
            }
            if (!(frame instanceof Http2WindowUpdate)
                && streamId != 0
                && blockedStreams != null
                && blockedStreams.contains(streamId)) {
                continue;
            }

            if (!(frame instanceof Http2DataFrame) || frame.flowControlSize() == 0) {
                iterator.remove();
                return new WritableFrame(
                    frame,
                    task,
                    true,
                    pending.protocolError
                );
            }
            Http2DataFrame data = (Http2DataFrame) frame;
            int remaining = data.payloadLength() - pending.dataBytesWritten;
            int reserved = reserveCreditUpTo(streamId, remaining);
            if (reserved < 0 || reserved > remaining) {
                throw new IllegalStateException("Invalid flow-control reservation " + reserved + " for " + remaining + " bytes");
            }
            if (reserved > 0) {
                boolean completesTask = reserved == remaining;
                var writableData = new Http2DataFrame(
                    streamId,
                    completesTask && data.endStream(),
                    data.payload(),
                    data.payloadOffset() + pending.dataBytesWritten,
                    reserved
                );
                pending.dataBytesWritten += reserved;
                if (completesTask) {
                    iterator.remove();
                }
                return new WritableFrame(
                    writableData,
                    task,
                    completesTask,
                    pending.protocolError
                );
            }

            if (streamId != 0) {
                if (blockedStreams == null) {
                    blockedStreams = new HashSet<>();
                }
                blockedStreams.add(streamId);
            }
        }
        return null;
    }

    boolean isIdle() {
        return mailbox.isEmpty() && pendingWrites.isEmpty();
    }

    boolean hasCommands() {
        return !mailbox.isEmpty();
    }

    void failAll(Exception reason) {
        processAvailableCommands();
        PendingWrite pending;
        while ((pending = pendingWrites.poll()) != null) {
            pending.task.fail(reason);
        }
    }

    private void apply(Command command) {
        if (command instanceof QueueWrite) {
            QueueWrite write = (QueueWrite) command;
            queue(write.task, false, write.retainResetState);
        } else if (command instanceof ResetStream) {
            ResetStream reset = (ResetStream) command;
            // Retirement may already have run before this reset command. A retired
            // stream rejects later writes without needing another persistent record.
            if (streamStates.containsKey(reset.streamId)) {
                peerResetStreams.add(reset.streamId);
            }
            streamCredits.remove(reset.streamId);
            applyResetState(reset.streamId);
            markProtocolStateClosed(reset.streamId);
            if (reset.stream != null) {
                reset.stream.applyPeerReset(reset.resetFrame);
            }
            failPendingStreamWrites(reset.streamId, reset.reason, true);
            removeStreamIfReady(reset.streamId);
        } else if (command instanceof RemoteEndStream) {
            applyRemoteEndStream(((RemoteEndStream) command).streamId);
        } else if (command instanceof OpenStream) {
            OpenStream open = (OpenStream) command;
            forgetResetState(open.streamId);
            streamsPendingRemoval.remove(open.streamId);
            streamCredits.put(open.streamId, open.initialCredit);
            streamStates.put(open.streamId, open.initialState);
            if (open.stream == null) {
                applicationStreams.remove(open.streamId);
            } else {
                applicationStreams.put(open.streamId, open.stream);
            }
        } else if (command instanceof ForgetStream) {
            int streamId = ((ForgetStream) command).streamId;
            streamsPendingRemoval.add(streamId);
            removeStreamIfReady(streamId);
        } else if (command instanceof ApplicationExchangeEnded) {
            int streamId = ((ApplicationExchangeEnded) command).streamId;
            streamsPendingRemoval.add(streamId);
            removeStreamIfReady(streamId);
        } else if (command instanceof ConnectionWindowUpdate) {
            ConnectionWindowUpdate update = (ConnectionWindowUpdate) command;
            if (connectionErrorPendingGoAway) {
                return;
            }
            try {
                connectionCredit = addCredit(connectionCredit, update.increment, 0);
            } catch (Http2Exception e) {
                queueConnectionError(e, update.lastStreamId);
            }
        } else if (command instanceof StreamWindowUpdate) {
            StreamWindowUpdate update = (StreamWindowUpdate) command;
            Integer currentCredit = streamCredits.get(update.streamId);
            Http2StreamState state = streamStates.get(update.streamId);
            if (currentCredit != null
                && (state != Http2StreamState.CLOSED || hasPendingEndStreamWrite(update.streamId))) {
                try {
                    streamCredits.put(update.streamId, addCredit(currentCredit, update.increment, update.streamId));
                } catch (Http2Exception e) {
                    queueStreamError(e);
                }
            }
        } else if (command instanceof PeerSettingsChange) {
            PeerSettingsChange change = (PeerSettingsChange) command;
            if (connectionErrorPendingGoAway) {
                return;
            }
            try {
                if (change.initialWindowDifference != 0) {
                    Map<Integer, Integer> changedCredits = new HashMap<>(streamCredits.size());
                    for (Map.Entry<Integer, Integer> entry : streamCredits.entrySet()) {
                        changedCredits.put(
                            entry.getKey(),
                            addCredit(
                                entry.getValue(),
                                change.initialWindowDifference,
                                entry.getKey()
                            )
                        );
                    }
                    streamCredits.putAll(changedCredits);
                }
                fieldBlockEncoder.changeTableSize(change.headerTableSize);
                queue(change.acknowledgement, true, ResetRetention.NONE);
            } catch (Http2Exception e) {
                queueConnectionError(
                    Http2Exception.connection(e.errorCode(), e.getMessage()),
                    change.lastStreamId
                );
            }
        } else if (command == SettingsTimeout.INSTANCE) {
            queueConnectionError(
                Http2Exception.connection(
                    Http2ErrorCode.SETTINGS_TIMEOUT,
                    "Timed out waiting for SETTINGS ack"
                ),
                0
            );
        } else if (command instanceof ConnectionFailure) {
            ConnectionFailure failure = (ConnectionFailure) command;
            if (beginConnectionError(failure.reason)) {
                addPending(failure.goAway, true, null);
            } else {
                failure.goAway.fail(new IOException("A connection error is already being written"));
            }
        } else if (command == WakeUp.INSTANCE) {
            wakeUpQueued.set(false);
        }
    }

    private void queue(WriteTask task, boolean first, ResetRetention retainResetState) {
        queue(task, first, retainResetState, null);
    }

    private void queue(
        WriteTask task,
        boolean first,
        ResetRetention retainResetState,
        @Nullable Http2Exception protocolError
    ) {
        LogicalHttp2Frame frame = task.frame();
        int streamId = frame.streamId();
        if (connectionFailureReason != null) {
            task.fail(connectionFailureReason);
        } else if (frame instanceof Http2ResetStreamFrame) {
            IOException resetReason = new IOException("Stream " + streamId + " was locally reset");
            streamCredits.remove(streamId);
            applyResetState(streamId);
            localResetsPendingWrite.add(streamId);
            // The caller's retention decision can precede a queued retirement.
            if (retainResetState == ResetRetention.UNTIL_APPLICATION_ENDS && streamStates.containsKey(streamId)) {
                retainedLocalResetStreams.add(streamId);
            }
            // RFC 9113 Section 5.4.2 permits additional RST_STREAM responses to frames that
            // continue arriving on a closed stream. Preserve those resets while discarding
            // every other unsent frame for the stream.
            failPendingStreamWrites(streamId, resetReason, false);
            addPending(task, first, protocolError);
            removeStreamIfReady(streamId);
        } else if (streamId != 0 && (peerResetStreams.contains(streamId)
            || retainedLocalResetStreams.contains(streamId)
            || localResetsPendingWrite.contains(streamId))) {
            task.fail(new IOException("Stream " + streamId + " was reset before " + frame.getClass().getSimpleName() + " could be written"));
        } else if (streamId != 0 && !canQueueFrame(frame)) {
            task.fail(new IOException("Stream " + streamId + " was not open for " + frame.getClass().getSimpleName()));
        } else {
            if (frame.endStream()) {
                Http2StreamState state = streamStates.get(streamId);
                if (state == null) {
                    throw new IllegalStateException("Missing protocol state for stream " + streamId);
                }
                streamStates.put(streamId, state.localEndStream());
            }
            addPending(task, first, protocolError);
        }
    }

    private boolean canQueueFrame(LogicalHttp2Frame frame) {
        Http2StreamState state = streamStates.get(frame.streamId());
        if (state == null || state == Http2StreamState.CLOSED) {
            return false;
        }
        if (frame instanceof Http2HeadersFrame || frame instanceof Http2DataFrame) {
            return state.canSendEndStream();
        }
        return true;
    }

    private void applyRemoteEndStream(int streamId) {
        Http2StreamState state = streamStates.get(streamId);
        if (state == null || state == Http2StreamState.CLOSED) {
            return;
        }
        Http2StreamState newState = state.remoteEndStream();
        streamStates.put(streamId, newState);
        if (newState == Http2StreamState.CLOSED && !hasPendingEndStreamWrite(streamId)) {
            markProtocolStateClosed(streamId);
        }
        removeStreamIfReady(streamId);
    }

    private void markProtocolStateClosed(int streamId) {
        Http2Stream stream = applicationStreams.get(streamId);
        if (stream != null) {
            stream.onProtocolStateClosed();
        }
    }

    private void applyResetState(int streamId) {
        if (streamStates.containsKey(streamId)) {
            streamStates.put(streamId, Http2StreamState.CLOSED);
        }
        Http2Stream stream = applicationStreams.get(streamId);
        if (stream != null) {
            stream.onProtocolResetApplied();
        }
    }

    private void queueConnectionError(Http2Exception error, int lastStreamId) {
        if (!beginConnectionError(new IOException("HTTP/2 connection error", error))) {
            return;
        }
        addPending(
            new WriteTask(new Http2GoAway(lastStreamId, error.errorCode().code(), null), false),
            true,
            error
        );
    }

    private boolean beginConnectionError(IOException reason) {
        if (connectionErrorPendingGoAway) {
            return false;
        }
        connectionErrorPendingGoAway = true;
        connectionFailureReason = reason;
        for (Map.Entry<Integer, Http2StreamState> entry : streamStates.entrySet()) {
            entry.setValue(Http2StreamState.CLOSED);
        }
        for (Http2Stream stream : applicationStreams.values()) {
            stream.onProtocolResetApplied();
        }
        PendingWrite pending;
        while ((pending = pendingWrites.poll()) != null) {
            pending.task.fail(reason);
        }
        return true;
    }

    private void queueStreamError(Http2Exception error) {
        queue(
            new WriteTask(new Http2ResetStreamFrame(error.streamId(), error.errorCode().code()), false),
            true,
            ResetRetention.UNTIL_APPLICATION_ENDS,
            error
        );
    }

    private void addPending(WriteTask task, boolean first, @Nullable Http2Exception protocolError) {
        PendingWrite pending = new PendingWrite(task, protocolError);
        if (first) {
            pendingWrites.addFirst(pending);
        } else {
            pendingWrites.addLast(pending);
        }
    }

    private void failPendingStreamWrites(int streamId, IOException reason, boolean includeResets) {
        for (Iterator<PendingWrite> iterator = pendingWrites.iterator(); iterator.hasNext(); ) {
            PendingWrite pending = iterator.next();
            LogicalHttp2Frame frame = pending.task.frame();
            if (frame.streamId() == streamId && (includeResets || !(frame instanceof Http2ResetStreamFrame))) {
                iterator.remove();
                pending.task.fail(reason);
            }
        }
    }

    private boolean hasPendingReset(int streamId) {
        for (PendingWrite pending : pendingWrites) {
            LogicalHttp2Frame frame = pending.task.frame();
            if (frame.streamId() == streamId && frame instanceof Http2ResetStreamFrame) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPendingWrite(int streamId) {
        for (PendingWrite pending : pendingWrites) {
            if (pending.task.frame().streamId() == streamId) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPendingEndStreamWrite(int streamId) {
        for (PendingWrite pending : pendingWrites) {
            LogicalHttp2Frame frame = pending.task.frame();
            if (frame.streamId() == streamId && frame.endStream()) {
                return true;
            }
        }
        return false;
    }

    private void onWriteCompleted(LogicalHttp2Frame frame) {
        int streamId = frame.streamId();
        if (frame.endStream()) {
            Http2Stream stream = applicationStreams.get(streamId);
            if (stream != null) {
                stream.onLocalEndStreamPublished();
            }
        }
        if (frame instanceof Http2ResetStreamFrame
            || (frame.endStream() && streamStates.get(streamId) == Http2StreamState.CLOSED)) {
            markProtocolStateClosed(streamId);
        }
        removeStreamIfReady(streamId);
        if (frame instanceof Http2ResetStreamFrame && !hasPendingReset(streamId)) {
            localResetsPendingWrite.remove(streamId);
        }
    }

    private void removeStreamIfReady(int streamId) {
        if (!streamsPendingRemoval.contains(streamId) || hasPendingWrite(streamId)) {
            return;
        }
        if (applicationStreams.containsKey(streamId)
            && streamStates.get(streamId) != Http2StreamState.CLOSED) {
            return;
        }
        removeStream(streamId);
    }

    private void removeStream(int streamId) {
        streamsPendingRemoval.remove(streamId);
        streamCredits.remove(streamId);
        streamStates.remove(streamId);
        forgetResetState(streamId);
        Http2Stream stream = applicationStreams.remove(streamId);
        if (stream != null) {
            stream.onProtocolStreamRetired();
        }
    }

    @Nullable Http2StreamState streamState(int streamId) {
        return streamStates.get(streamId);
    }

    // Inspect only from the coordinator owner, after queued commands have been processed.
    int resetRecordCount() {
        return peerResetStreams.size() + retainedLocalResetStreams.size() + localResetsPendingWrite.size();
    }

    private void forgetResetState(int streamId) {
        peerResetStreams.remove(streamId);
        retainedLocalResetStreams.remove(streamId);
        localResetsPendingWrite.remove(streamId);
    }

    private int reserveCreditUpTo(int streamId, int requested) {
        Integer streamCredit = streamCredits.get(streamId);
        if (streamCredit == null) {
            return 0;
        }

        int reservable = Math.min(requested, Math.max(0, connectionCredit));
        if (reservable == 0) {
            return 0;
        }

        reservable = Math.min(reservable, Math.max(0, streamCredit));
        if (reservable == 0) {
            return 0;
        }
        streamCredits.put(streamId, streamCredit - reservable);

        connectionCredit -= reservable;
        return reservable;
    }

    private static int addCredit(int currentCredit, int increment, int streamId) throws Http2Exception {
        try {
            return Math.addExact(currentCredit, increment);
        } catch (ArithmeticException e) {
            throw streamId == 0
                ? Http2Exception.connection(Http2ErrorCode.FLOW_CONTROL_ERROR, "Credit overflow")
                : Http2Exception.stream(Http2ErrorCode.FLOW_CONTROL_ERROR, "Credit overflow", streamId);
        }
    }

    private static final class PendingWrite {
        private final WriteTask task;
        private final @Nullable Http2Exception protocolError;
        private int dataBytesWritten;

        private PendingWrite(WriteTask task, @Nullable Http2Exception protocolError) {
            this.task = task;
            this.protocolError = protocolError;
        }
    }
}
