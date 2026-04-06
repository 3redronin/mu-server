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

class Http2BodyInputStream extends InputStream {

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
                if (remaining == 0 && data.endStream()) {
                    return -1;
                }
                int readAmount;
                int creditToReturn;
                if (remaining < len) {
                    // the user wants more than what is available in the first frame, so give all the rest of it
                    System.arraycopy(data.payload(), offset, b, off, remaining);
                    // TODO: how about waiting for more data?
                    pending.currentOffset += remaining;
                    if (!data.endStream()) {
                        frames.remove();
                    }
                    readAmount = remaining;
                } else {
                    // the user wants less than what is available
                    System.arraycopy(data.payload(), offset, b, off, len);
                    pending.currentOffset += len;
                    readAmount = len;
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
            } else if (frame instanceof IOException) {
                throw (IOException) frame;
            } else {
                // not possible, I swear
                throw new IllegalStateException("Unexpected frame type: " + frame.getClass().getName());
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
                hasData.signal();
            } finally {
                lock.unlock();
            }
        }
    }

    void onStreamReset(Http2ResetStreamFrame reset) {
        Http2ErrorCode err = Objects.requireNonNullElse(reset.errorCodeEnum(), Http2ErrorCode.INTERNAL_ERROR);
        var ex = (err == Http2ErrorCode.CANCEL)
            ? new EOFException("Client cancelled the request")
            : new IOException("Error reading request body: " + err.name() + " (" + err.code() + ")");
        setErrored(ex);
    }

    private void setErrored(IOException ex) {
        int discardedCredit = 0;
        lock.lock();
        try {
            if (!isErrored()) {
                for (Object frame : frames) {
                    if (frame instanceof PendingDataFrame) {
                        discardedCredit += ((PendingDataFrame) frame).unreadCredit();
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
        setErrored(reason);
    }


}
