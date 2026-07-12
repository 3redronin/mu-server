package io.muserver;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class Http2BodyInputStream extends InputStream implements RequestTrailersAccessor {

    private static class PendingDataFrame {
        final Http2DataFrame dataFrame;
        final int flowControlSize;
        int currentOffset;
        int creditReturned;

        PendingDataFrame(Http2DataFrame dataFrame, int flowControlSize) {
            this.dataFrame = dataFrame;
            this.flowControlSize = flowControlSize;
        }

        int unreadCredit() {
            return flowControlSize - creditReturned;
        }
    }

    private final long readTimeoutMillis;
    private final CreditAvailableListener onDataReadCallback;
    private final CreditAvailableListener onDataDiscardedCallback;
    private final Lock lock = new ReentrantLock();
    private final Queue<Object> frames = new LinkedList<>();
    private final Condition hasData = lock.newCondition();
    private boolean requestBodyComplete;
    private @org.jspecify.annotations.Nullable FieldBlock trailers;

    private static final class EndOfStreamMarker {
        private EndOfStreamMarker() {
        }
    }

    private static final EndOfStreamMarker END_OF_STREAM = new EndOfStreamMarker();

    Http2BodyInputStream(long readTimeoutMillis, CreditAvailableListener onDataReadCallback, CreditAvailableListener onDataDiscardedCallback) {
        this.readTimeoutMillis = readTimeoutMillis;
        this.onDataReadCallback = onDataReadCallback;
        this.onDataDiscardedCallback = onDataDiscardedCallback;
    }

    @Override
    public int read() throws IOException {
        var buffer = new byte[1];
        var read = read(buffer, 0, 1);
        if (read == -1) return -1;
        return buffer[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        lock.lock();
        try {
            while (true) {
                Object frame;
                while ((frame = frames.peek()) == null) {
                    try {
                        if (!hasData.await(readTimeoutMillis, TimeUnit.MILLISECONDS)) {
                            throw new IOException("Timed out waiting for data");
                        }
                    } catch (InterruptedException e) {
                        throw new InterruptedIOException("Interrupted waiting for data");
                    }
                }

                if (frame instanceof PendingDataFrame) {
                    var pending = (PendingDataFrame) frame;
                    var data = pending.dataFrame;

                    int offset = data.payloadOffset() + pending.currentOffset;
                    int remaining = data.payloadLength() - pending.currentOffset;
                    if (remaining == 0) {
                        if (data.endStream()) {
                            return -1;
                        }
                        frames.remove();
                        continue;
                    }
                    int readAmount;
                    int creditToReturn;
                    if (remaining < len) {
                        // the user wants more than what is available in the first frame, so give all the rest of it
                        System.arraycopy(data.payload(), offset, b, off, remaining);
                        // TODO: how about waiting for more data?
                        pending.currentOffset += remaining;
                        readAmount = remaining;
                    } else {
                        // the user wants less than what is available
                        System.arraycopy(data.payload(), offset, b, off, len);
                        pending.currentOffset += len;
                        readAmount = len;
                    }

                    if (pending.currentOffset == data.payloadLength() && !data.endStream()) {
                        frames.remove();
                    }

                    creditToReturn = readAmount;
                    if (pending.currentOffset == data.payloadLength()) {
                        creditToReturn += pending.flowControlSize - data.payloadLength();
                    }
                    pending.creditReturned += creditToReturn;

                    try {
                        onDataReadCallback.creditAvailable(creditToReturn);
                    } catch (Http2Exception e) {
                        throw new IOException("Http2 error updating flow control", e);
                    }
                    return readAmount;
                } else if (frame == END_OF_STREAM) {
                    return -1;
                } else if (frame instanceof IOException) {
                    throw (IOException) frame;
                } else {
                    // not possible, I swear
                    throw new IllegalStateException("Unexpected frame type: " + frame.getClass().getName());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    public void onData(Http2DataFrame dataFrame) {
        onData(dataFrame, dataFrame.flowControlSize());
    }

    public void onData(Http2DataFrame dataFrame, int flowControlSize) {
        if (dataFrame.payloadLength() > 0 || dataFrame.endStream()) {
            lock.lock();
            try {
                frames.add(new PendingDataFrame(dataFrame, flowControlSize));
                if (dataFrame.endStream()) {
                    requestBodyComplete = true;
                }
                hasData.signal();
            } finally {
                lock.unlock();
            }
        }
    }

    void onTrailers(FieldBlock trailers) {
        lock.lock();
        try {
            this.trailers = trailers;
            this.requestBodyComplete = true;
            frames.add(END_OF_STREAM);
            hasData.signal();
        } finally {
            lock.unlock();
        }
    }

    void onStreamReset(Http2ResetStreamFrame reset) {
        Http2ErrorCode err = Objects.requireNonNullElse(reset.errorCodeEnum(), Http2ErrorCode.INTERNAL_ERROR);
        var ex = (err == Http2ErrorCode.CANCEL)
            ? new EOFException("Client cancelled the request")
            : new IOException("Error reading request body: " + err.name() + " (" + err.code() + ")");
        setErrored(ex, true);
    }

    private void setErrored(IOException ex, boolean refundUnreadData) {
        int discardedCredit = 0;
        lock.lock();
        try {
            if (!isErrored()) {
                if (refundUnreadData) {
                    for (Object frame : frames) {
                        if (frame instanceof PendingDataFrame) {
                            discardedCredit += ((PendingDataFrame) frame).unreadCredit();
                        }
                    }
                }
                frames.clear();
                frames.add(ex);
                hasData.signal();
            }
        } finally {
            lock.unlock();
        }
        if (discardedCredit > 0) {
            try {
                onDataDiscardedCallback.creditAvailable(discardedCredit);
            } catch (Http2Exception e) {
                throw new IllegalStateException("Could not refund unread HTTP/2 data credit", e);
            }
        }
    }

    private boolean isErrored() {
        return frames.size() == 1 && frames.peek() instanceof Exception;
    }

    void cancel(IOException reason) {
        cancel(reason, true);
    }

    void cancel(IOException reason, boolean refundUnreadData) {
        setErrored(reason, refundUnreadData);
    }


    @Override
    public boolean isRequestBodyComplete() {
        lock.lock();
        try {
            if (requestBodyComplete) {
                return true;
            }
            Object frame = frames.peek();
            if (frame instanceof PendingDataFrame) {
                var pending = (PendingDataFrame) frame;
                return pending.dataFrame.endStream() && pending.currentOffset == pending.dataFrame.payloadLength();
            }
            return frame == END_OF_STREAM;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public @org.jspecify.annotations.Nullable Headers trailers() {
        return trailers;
    }


}
