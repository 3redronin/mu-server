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

    private final long readTimeoutMillis;
    private final CreditAvailableListener onDataReadCallback;
    private final Lock lock = new ReentrantLock();
    private final Queue<Object> frames = new LinkedList<>();
    private final Condition hasData = lock.newCondition();
    private int currentOffset = 0;

    Http2BodyInputStream(long readTimeoutMillis, CreditAvailableListener onDataReadCallback) {
        this.readTimeoutMillis = readTimeoutMillis;
        this.onDataReadCallback = onDataReadCallback;
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

            if (frame instanceof Http2DataFrame) {
                var data = (Http2DataFrame) frame;

                int offset = data.payloadOffset() + currentOffset;
                int remaining = data.payloadLength() - currentOffset;
                if (remaining == 0 && data.endStream()) {
                    return -1;
                }
                int readAmount;
                if (remaining < len) {
                    // the user wants more than what is available in the first frame, so give all the rest of it
                    System.arraycopy(data.payload(), offset, b, off, remaining);
//                if (remaining == len) {
                    // TODO: how about waiting for more data?
//                }
                    if (!data.endStream()) {
                        currentOffset = 0;
                        frames.remove();
                    } else {
                        currentOffset += remaining;
                    }
                    readAmount = remaining;
                } else {
                    // the user wants less than what is available
                    System.arraycopy(data.payload(), offset, b, off, len);
                    currentOffset += len;
                    readAmount = len;
                }
                try {
                    onDataReadCallback.creditAvailable(readAmount);
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
        if (dataFrame.payloadLength() > 0 || dataFrame.endStream()) {
            lock.lock();
            try {
                frames.add(dataFrame);
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
        lock.lock();
        try {
            if (!isErrored()) {
                frames.clear();
                frames.add(ex);
                hasData.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean isErrored() {
        return frames.size() == 1 && frames.peek() instanceof Exception;
    }

    void cancel(IOException reason) {
        setErrored(reason);
    }


}
