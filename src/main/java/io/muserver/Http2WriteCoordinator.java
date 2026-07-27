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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The multiple-producer, single-consumer mailbox and pending-write scheduler for an HTTP/2 connection.
 *
 * <p>Application and reader threads only submit commands. The connection writer is the sole caller of
 * the command-processing, scheduling, and failure methods.</p>
 */
final class Http2WriteCoordinator {

    static final class CommandResult {
        private final CountDownLatch completed = new CountDownLatch(1);
        private volatile @Nullable Http2Exception error;

        void await() throws InterruptedException, Http2Exception {
            completed.await();
            Http2Exception currentError = error;
            if (currentError != null) {
                throw currentError;
            }
        }

        private void complete() {
            completed.countDown();
        }

        private void fail(Http2Exception error) {
            this.error = error;
            completed.countDown();
        }
    }

    static final class WritableFrame {
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

        void complete() {
            if (completesTask) {
                task.complete();
            }
        }

        void fail(Exception reason) {
            task.fail(reason);
        }
    }

    private interface Command {
    }

    private static final class QueueWrite implements Command {
        private final WriteTask task;
        private final boolean first;
        private final boolean retainResetState;

        private QueueWrite(WriteTask task, boolean first, boolean retainResetState) {
            this.task = task;
            this.first = first;
            this.retainResetState = retainResetState;
        }
    }

    private static final class ResetStream implements Command {
        private final int streamId;
        private final IOException reason;
        private final @Nullable Http2Stream stream;
        private final @Nullable CommandResult result;

        private ResetStream(int streamId, IOException reason, @Nullable Http2Stream stream, @Nullable CommandResult result) {
            this.streamId = streamId;
            this.reason = reason;
            this.stream = stream;
            this.result = result;
        }
    }

    private static final class OpenStream implements Command {
        private final int streamId;
        private final int initialCredit;

        private OpenStream(int streamId, int initialCredit) {
            this.streamId = streamId;
            this.initialCredit = initialCredit;
        }
    }

    private static final class ForgetStream implements Command {
        private final int streamId;

        private ForgetStream(int streamId) {
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

    private static final class InitialWindowSizeChange implements Command {
        private final int difference;
        private final WriteTask acknowledgement;
        private final int lastStreamId;

        private InitialWindowSizeChange(int difference, WriteTask acknowledgement, int lastStreamId) {
            this.difference = difference;
            this.acknowledgement = acknowledgement;
            this.lastStreamId = lastStreamId;
        }
    }

    private enum WakeUp implements Command {
        INSTANCE
    }

    private final BlockingQueue<Command> mailbox = new LinkedBlockingQueue<>();
    private final ArrayDeque<PendingWrite> pendingWrites = new ArrayDeque<>();
    private final Set<Integer> peerResetStreams = new HashSet<>();
    private final Set<Integer> retainedLocalResetStreams = new HashSet<>();
    private final Set<Integer> localResetsPendingWrite = new HashSet<>();
    private final Set<Integer> forgottenStreams = new HashSet<>();
    private final Map<Integer, Integer> streamCredits = new HashMap<>();
    private final ArrayList<Command> commandBatch = new ArrayList<>();
    private final AtomicBoolean wakeUpQueued = new AtomicBoolean();
    private int connectionCredit;
    private boolean connectionErrorPendingGoAway;

    Http2WriteCoordinator(int initialConnectionCredit) {
        if (initialConnectionCredit < 0) {
            throw new IllegalArgumentException("Initial connection credit cannot be negative");
        }
        this.connectionCredit = initialConnectionCredit;
    }

    void submit(WriteTask task) {
        submit(task, true);
    }

    void submit(WriteTask task, boolean retainResetState) {
        mailbox.add(new QueueWrite(task, false, retainResetState));
    }

    void submitFirst(WriteTask task) {
        mailbox.add(new QueueWrite(task, true, false));
    }

    CommandResult resetStream(int streamId, IOException reason, @Nullable Http2Stream stream) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("A reset stream ID must be positive");
        }
        CommandResult result = new CommandResult();
        mailbox.add(new ResetStream(streamId, reason, stream, result));
        return result;
    }

    void openStream(int streamId, int initialCredit) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("An open stream ID must be positive");
        }
        if (initialCredit < 0) {
            throw new IllegalArgumentException("Initial stream credit cannot be negative");
        }
        mailbox.add(new OpenStream(streamId, initialCredit));
    }

    void forgetStream(int streamId) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("A forgotten stream ID must be positive");
        }
        mailbox.add(new ForgetStream(streamId));
    }

    void applyConnectionWindowUpdate(int increment, int lastStreamId) {
        if (increment <= 0) {
            throw new IllegalArgumentException("A connection window increment must be positive");
        }
        if (lastStreamId < 0) {
            throw new IllegalArgumentException("The last stream ID cannot be negative");
        }
        mailbox.add(new ConnectionWindowUpdate(increment, lastStreamId));
    }

    void applyStreamWindowUpdate(int streamId, int increment) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("A stream window update requires a positive stream ID");
        }
        if (increment <= 0) {
            throw new IllegalArgumentException("A stream window increment must be positive");
        }
        mailbox.add(new StreamWindowUpdate(streamId, increment));
    }

    void applyInitialWindowSizeChange(int difference, WriteTask acknowledgement, int lastStreamId) {
        if (lastStreamId < 0) {
            throw new IllegalArgumentException("The last stream ID cannot be negative");
        }
        mailbox.add(new InitialWindowSizeChange(difference, acknowledgement, lastStreamId));
    }

    void wakeUp() {
        if (wakeUpQueued.compareAndSet(false, true)) {
            mailbox.add(WakeUp.INSTANCE);
        }
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
     * Waits for a command and applies it and the commands that arrived with it.
     *
     * @param timeoutMillis a positive timeout, or a negative value to wait indefinitely
     */
    void awaitCommand(long timeoutMillis) throws InterruptedException {
        Command command = timeoutMillis < 0
            ? mailbox.take()
            : mailbox.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        if (command != null) {
            apply(command);
            processAvailableCommands();
        }
    }

    /**
     * Returns and removes the first pending write that can reserve its flow-control credit.
     *
     * <p>A blocked frame prevents later frames for the same stream from overtaking it. Frames for other
     * streams and connection-level frames remain eligible, so one flow-controlled stream cannot block
     * the connection.</p>
     */
    @Nullable WritableFrame pollWritable() {
        Set<Integer> blockedStreams = null;
        for (Iterator<PendingWrite> iterator = pendingWrites.iterator(); iterator.hasNext(); ) {
            PendingWrite pending = iterator.next();
            WriteTask task = pending.task;
            int streamId = task.frame().streamId();
            LogicalHttp2Frame frame = task.frame();
            if (connectionErrorPendingGoAway && !(frame instanceof Http2GoAway)) {
                continue;
            }
            if (streamId != 0 && blockedStreams != null && blockedStreams.contains(streamId)) {
                continue;
            }

            if (!(frame instanceof Http2DataFrame) || frame.flowControlSize() == 0) {
                iterator.remove();
                removeCreditIfForgottenAndDrained(streamId);
                if (frame instanceof Http2ResetStreamFrame && !hasPendingReset(streamId)) {
                    localResetsPendingWrite.remove(streamId);
                }
                return new WritableFrame(frame, task, true, pending.protocolError);
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
                    removeCreditIfForgottenAndDrained(streamId);
                }
                return new WritableFrame(writableData, task, completesTask, pending.protocolError);
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
            queue(write.task, write.first, write.retainResetState);
        } else if (command instanceof ResetStream) {
            ResetStream reset = (ResetStream) command;
            peerResetStreams.add(reset.streamId);
            forgottenStreams.remove(reset.streamId);
            streamCredits.remove(reset.streamId);
            failPendingStreamWrites(reset.streamId, reset.reason, true);
            if (reset.stream != null) {
                reset.stream.resetProtocolState();
            }
            if (reset.result != null) {
                reset.result.complete();
            }
        } else if (command instanceof OpenStream) {
            OpenStream open = (OpenStream) command;
            forgetResetState(open.streamId);
            forgottenStreams.remove(open.streamId);
            streamCredits.put(open.streamId, open.initialCredit);
        } else if (command instanceof ForgetStream) {
            int streamId = ((ForgetStream) command).streamId;
            forgetResetState(streamId);
            if (hasPendingWrite(streamId)) {
                forgottenStreams.add(streamId);
            } else {
                forgottenStreams.remove(streamId);
                streamCredits.remove(streamId);
            }
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
            if (currentCredit != null) {
                try {
                    streamCredits.put(update.streamId, addCredit(currentCredit, update.increment, update.streamId));
                } catch (Http2Exception e) {
                    queueStreamError(e);
                }
            }
        } else if (command instanceof InitialWindowSizeChange) {
            InitialWindowSizeChange change = (InitialWindowSizeChange) command;
            if (connectionErrorPendingGoAway) {
                return;
            }
            try {
                if (change.difference != 0) {
                    Map<Integer, Integer> changedCredits = new HashMap<>(streamCredits.size());
                    for (Map.Entry<Integer, Integer> entry : streamCredits.entrySet()) {
                        changedCredits.put(
                            entry.getKey(),
                            addCredit(entry.getValue(), change.difference, entry.getKey())
                        );
                    }
                    streamCredits.putAll(changedCredits);
                }
                queue(change.acknowledgement, true, false);
            } catch (Http2Exception e) {
                queueConnectionError(
                    Http2Exception.connection(e.errorCode(), e.getMessage()),
                    change.lastStreamId
                );
            }
        } else if (command == WakeUp.INSTANCE) {
            wakeUpQueued.set(false);
        }
    }

    private void queue(WriteTask task, boolean first, boolean retainResetState) {
        queue(task, first, retainResetState, null);
    }

    private void queue(
        WriteTask task,
        boolean first,
        boolean retainResetState,
        @Nullable Http2Exception protocolError
    ) {
        LogicalHttp2Frame frame = task.frame();
        int streamId = frame.streamId();
        if (frame instanceof Http2ResetStreamFrame) {
            IOException resetReason = new IOException("Stream " + streamId + " was locally reset");
            forgottenStreams.remove(streamId);
            streamCredits.remove(streamId);
            localResetsPendingWrite.add(streamId);
            if (retainResetState) {
                retainedLocalResetStreams.add(streamId);
            }
            // RFC 9113 Section 5.4.2 permits additional RST_STREAM responses to frames that
            // continue arriving on a closed stream. Preserve those resets while discarding
            // every other unsent frame for the stream.
            failPendingStreamWrites(streamId, resetReason, false);
            addPending(task, first, protocolError);
        } else if (streamId != 0 && (peerResetStreams.contains(streamId)
            || retainedLocalResetStreams.contains(streamId)
            || localResetsPendingWrite.contains(streamId))) {
            task.fail(new IOException("Stream " + streamId + " was reset before " + frame.getClass().getSimpleName() + " could be written"));
        } else if (frame instanceof Http2DataFrame && frame.flowControlSize() > 0
            && !streamCredits.containsKey(streamId)) {
            task.fail(new IOException("Stream " + streamId + " was not open when DATA was queued"));
        } else {
            addPending(task, first, protocolError);
        }
    }

    private void queueConnectionError(Http2Exception error, int lastStreamId) {
        if (connectionErrorPendingGoAway) {
            return;
        }
        connectionErrorPendingGoAway = true;
        queue(
            new WriteTask(new Http2GoAway(lastStreamId, error.errorCode().code(), null), false),
            true,
            false,
            error
        );
    }

    private void queueStreamError(Http2Exception error) {
        queue(
            new WriteTask(new Http2ResetStreamFrame(error.streamId(), error.errorCode().code()), false),
            true,
            true,
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

    private void removeCreditIfForgottenAndDrained(int streamId) {
        if (forgottenStreams.contains(streamId) && !hasPendingWrite(streamId)) {
            forgottenStreams.remove(streamId);
            streamCredits.remove(streamId);
        }
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
