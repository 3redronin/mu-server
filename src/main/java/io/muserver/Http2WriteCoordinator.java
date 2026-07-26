package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
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

    interface CreditReservation {
        /**
         * Reserves up to {@code requested} bytes of stream and connection flow-control credit.
         *
         * @return the number of bytes reserved, or zero if no DATA can currently be sent
         */
        int reserveUpTo(int streamId, int requested);
    }

    static final class WritableFrame {
        private final LogicalHttp2Frame frame;
        private final WriteTask task;
        private final boolean completesTask;

        private WritableFrame(LogicalHttp2Frame frame, WriteTask task, boolean completesTask) {
            this.frame = frame;
            this.task = task;
            this.completesTask = completesTask;
        }

        LogicalHttp2Frame frame() {
            return frame;
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

        private ResetStream(int streamId, IOException reason) {
            this.streamId = streamId;
            this.reason = reason;
        }
    }

    private static final class OpenStream implements Command {
        private final int streamId;

        private OpenStream(int streamId) {
            this.streamId = streamId;
        }
    }

    private static final class ForgetStream implements Command {
        private final int streamId;

        private ForgetStream(int streamId) {
            this.streamId = streamId;
        }
    }

    private static final class ResumeDataAfterWrite implements Command {
        private final WriteTask task;

        private ResumeDataAfterWrite(WriteTask task) {
            this.task = task;
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
    private final ArrayList<Command> commandBatch = new ArrayList<>();
    private final AtomicBoolean wakeUpQueued = new AtomicBoolean();
    private final AtomicBoolean dataSchedulingSuspended = new AtomicBoolean();

    void submit(WriteTask task) {
        submit(task, true);
    }

    void submit(WriteTask task, boolean retainResetState) {
        mailbox.add(new QueueWrite(task, false, retainResetState));
    }

    void submitFirst(WriteTask task) {
        mailbox.add(new QueueWrite(task, true, false));
    }

    void suspendDataScheduling() {
        dataSchedulingSuspended.set(true);
        wakeUp();
    }

    void submitFirstAndResumeData(WriteTask task) {
        mailbox.add(new ResumeDataAfterWrite(task));
    }

    void resetStream(int streamId, IOException reason) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("A reset stream ID must be positive");
        }
        mailbox.add(new ResetStream(streamId, reason));
    }

    void openStream(int streamId) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("An open stream ID must be positive");
        }
        mailbox.add(new OpenStream(streamId));
    }

    void forgetStream(int streamId) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("A forgotten stream ID must be positive");
        }
        mailbox.add(new ForgetStream(streamId));
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
    @Nullable WritableFrame pollWritable(CreditReservation creditReservation) {
        Set<Integer> blockedStreams = null;
        for (Iterator<PendingWrite> iterator = pendingWrites.iterator(); iterator.hasNext(); ) {
            PendingWrite pending = iterator.next();
            WriteTask task = pending.task;
            int streamId = task.frame().streamId();
            if (streamId != 0 && blockedStreams != null && blockedStreams.contains(streamId)) {
                continue;
            }

            LogicalHttp2Frame frame = task.frame();
            if (!(frame instanceof Http2DataFrame) || frame.flowControlSize() == 0) {
                iterator.remove();
                if (frame instanceof Http2ResetStreamFrame && !hasPendingReset(streamId)) {
                    localResetsPendingWrite.remove(streamId);
                }
                return new WritableFrame(frame, task, true);
            }
            if (dataSchedulingSuspended.get()) {
                if (blockedStreams == null) {
                    blockedStreams = new HashSet<>();
                }
                blockedStreams.add(streamId);
                continue;
            }

            Http2DataFrame data = (Http2DataFrame) frame;
            int remaining = data.payloadLength() - pending.dataBytesWritten;
            int reserved = creditReservation.reserveUpTo(streamId, remaining);
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
                return new WritableFrame(writableData, task, completesTask);
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
            failPendingStreamWrites(reset.streamId, reset.reason, true);
        } else if (command instanceof OpenStream) {
            forgetResetState(((OpenStream) command).streamId);
        } else if (command instanceof ForgetStream) {
            forgetResetState(((ForgetStream) command).streamId);
        } else if (command instanceof ResumeDataAfterWrite) {
            queue(((ResumeDataAfterWrite) command).task, true, false);
            dataSchedulingSuspended.set(false);
        } else if (command == WakeUp.INSTANCE) {
            wakeUpQueued.set(false);
        }
    }

    private void queue(WriteTask task, boolean first, boolean retainResetState) {
        LogicalHttp2Frame frame = task.frame();
        int streamId = frame.streamId();
        if (frame instanceof Http2ResetStreamFrame) {
            IOException resetReason = new IOException("Stream " + streamId + " was locally reset");
            localResetsPendingWrite.add(streamId);
            if (retainResetState) {
                retainedLocalResetStreams.add(streamId);
            }
            // RFC 9113 Section 5.4.2 permits additional RST_STREAM responses to frames that
            // continue arriving on a closed stream. Preserve those resets while discarding
            // every other unsent frame for the stream.
            failPendingStreamWrites(streamId, resetReason, false);
            addPending(task, first);
        } else if (streamId != 0 && (peerResetStreams.contains(streamId)
            || retainedLocalResetStreams.contains(streamId)
            || localResetsPendingWrite.contains(streamId))) {
            task.fail(new IOException("Stream " + streamId + " was reset before " + frame.getClass().getSimpleName() + " could be written"));
        } else {
            addPending(task, first);
        }
    }

    private void addPending(WriteTask task, boolean first) {
        PendingWrite pending = new PendingWrite(task);
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

    private void forgetResetState(int streamId) {
        peerResetStreams.remove(streamId);
        retainedLocalResetStreams.remove(streamId);
        localResetsPendingWrite.remove(streamId);
    }

    private static final class PendingWrite {
        private final WriteTask task;
        private int dataBytesWritten;

        private PendingWrite(WriteTask task) {
            this.task = task;
        }
    }
}
