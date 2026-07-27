package io.muserver;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Atomically accounts for the connection and stream receive windows.
 *
 * <p>The socket reader is the only caller that reserves credit, while request-body
 * consumers can return credit from application threads. A single short-held lock
 * keeps those two windows in one transaction without making input progress depend
 * on the independently scheduled, potentially blocking socket writer.</p>
 */
final class Http2InboundFlowControl {

    static final class Result {
        private final int streamId;
        private final int connectionUpdate;
        private final int streamUpdate;
        private final @Nullable Http2Exception error;

        private Result(
            int streamId,
            int connectionUpdate,
            int streamUpdate,
            @Nullable Http2Exception error
        ) {
            this.streamId = streamId;
            this.connectionUpdate = connectionUpdate;
            this.streamUpdate = streamUpdate;
            this.error = error;
        }

        int streamId() {
            return streamId;
        }

        int connectionUpdate() {
            return connectionUpdate;
        }

        int streamUpdate() {
            return streamUpdate;
        }

        @Nullable Http2Exception error() {
            return error;
        }
    }

    private static final class Window {
        private final int streamId;
        private final int updateThreshold;
        private int credit;
        private int pendingCredit;

        private Window(int streamId, int initialCredit) {
            this.streamId = streamId;
            this.credit = initialCredit;
            this.updateThreshold = initialCredit >>> 1;
        }

        private boolean reserve(int amount) {
            if (amount <= credit) {
                credit -= amount;
                return true;
            }
            return false;
        }

        private int returnCredit(int amount) throws Http2Exception {
            final int newCredit;
            final int newPendingCredit;
            try {
                newCredit = Math.addExact(credit, amount);
                newPendingCredit = Math.addExact(pendingCredit, amount);
            } catch (ArithmeticException e) {
                throw streamId == 0
                    ? Http2Exception.connection(Http2ErrorCode.FLOW_CONTROL_ERROR, "Credit overflow")
                    : Http2Exception.stream(
                        Http2ErrorCode.FLOW_CONTROL_ERROR,
                        "Credit overflow",
                        streamId
                    );
            }
            credit = newCredit;
            pendingCredit = newPendingCredit;
            if (pendingCredit >= updateThreshold) {
                int update = pendingCredit;
                pendingCredit = 0;
                return update;
            }
            return 0;
        }
    }

    private final Lock lock = new ReentrantLock();
    private final Window connectionWindow;
    private final Map<Integer, Window> streamWindows = new HashMap<>();

    Http2InboundFlowControl(int initialConnectionCredit) {
        if (initialConnectionCredit < 0) {
            throw new IllegalArgumentException("Initial connection credit cannot be negative");
        }
        connectionWindow = new Window(0, initialConnectionCredit);
    }

    void openStream(int streamId, int initialCredit) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("An inbound stream ID must be positive");
        }
        if (initialCredit < 0) {
            throw new IllegalArgumentException("Initial stream credit cannot be negative");
        }
        lock.lock();
        try {
            if (streamWindows.containsKey(streamId)) {
                throw new IllegalStateException("Inbound flow-control state already exists for stream " + streamId);
            }
            streamWindows.put(streamId, new Window(streamId, initialCredit));
        } finally {
            lock.unlock();
        }
    }

    void closeStream(int streamId) {
        lock.lock();
        try {
            streamWindows.remove(streamId);
        } finally {
            lock.unlock();
        }
    }

    Result reserve(int streamId, int amount) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("Inbound DATA requires a positive stream ID");
        }
        if (amount < 0) {
            throw new IllegalArgumentException("Inbound flow-control size cannot be negative");
        }
        lock.lock();
        try {
            if (!connectionWindow.reserve(amount)) {
                return new Result(
                    streamId,
                    0,
                    0,
                    Http2Exception.connection(
                        Http2ErrorCode.FLOW_CONTROL_ERROR,
                        "Connection flow control credit breach"
                    )
                );
            }

            Window streamWindow = streamWindows.get(streamId);
            if (streamWindow != null && streamWindow.reserve(amount)) {
                return new Result(streamId, 0, 0, null);
            }

            int connectionUpdate = returnReservedConnectionCredit(amount);
            Http2Exception error = streamWindow == null
                ? Http2Exception.stream(
                    Http2ErrorCode.STREAM_CLOSED,
                    "Received data on closed stream",
                    streamId
                )
                : Http2Exception.stream(
                    Http2ErrorCode.FLOW_CONTROL_ERROR,
                    "Not enough flow control credit for stream",
                    streamId
                );
            return new Result(streamId, connectionUpdate, 0, error);
        } finally {
            lock.unlock();
        }
    }

    Result returnCredit(int streamId, int amount, boolean includeStream) {
        if (streamId <= 0) {
            throw new IllegalArgumentException("Returned inbound credit requires a positive stream ID");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Returned inbound credit must be positive");
        }
        lock.lock();
        try {
            int connectionUpdate;
            try {
                connectionUpdate = connectionWindow.returnCredit(amount);
            } catch (Http2Exception connectionError) {
                return new Result(streamId, 0, 0, connectionError);
            }

            if (!includeStream) {
                return new Result(streamId, connectionUpdate, 0, null);
            }
            Window streamWindow = streamWindows.get(streamId);
            if (streamWindow == null) {
                return new Result(streamId, connectionUpdate, 0, null);
            }
            try {
                return new Result(
                    streamId,
                    connectionUpdate,
                    streamWindow.returnCredit(amount),
                    null
                );
            } catch (Http2Exception streamError) {
                return new Result(streamId, connectionUpdate, 0, streamError);
            }
        } finally {
            lock.unlock();
        }
    }

    private int returnReservedConnectionCredit(int amount) {
        try {
            return connectionWindow.returnCredit(amount);
        } catch (Http2Exception impossibleOverflow) {
            throw new IllegalStateException(
                "Returning credit reserved in the same transaction overflowed",
                impossibleOverflow
            );
        }
    }
}
