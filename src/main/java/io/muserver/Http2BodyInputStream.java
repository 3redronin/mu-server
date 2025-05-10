package io.muserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class Http2BodyInputStream extends InputStream {

    private final long readTimeoutMillis;
    private final Lock lock = new ReentrantLock();
    private final Queue<Http2DataFrame> frames = new LinkedList<>();
    private final Condition hasData = lock.newCondition();
    private int currentOffset = 0;

    Http2BodyInputStream(long readTimeoutMillis) {
        this.readTimeoutMillis = readTimeoutMillis;
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
            Http2DataFrame frame;
            while ((frame = frames.peek()) == null) {
                try {
                    if (!hasData.await(readTimeoutMillis, TimeUnit.MILLISECONDS)) {
                        throw new IOException("Timed out waiting for data");
                    }
                } catch (InterruptedException e) {
                    throw new InterruptedIOException("Interrupted waiting for data");
                }
            }

            int offset = frame.payloadOffset() + currentOffset;
            int remaining = frame.payloadLength() - currentOffset;
            if (remaining == 0 && frame.endStream()) {
                return -1;
            }
            if (remaining < len) {
                // the user wants more than what is available in the first frame, so give all the rest of it
                System.arraycopy(frame.payload(), offset, b, off, remaining);
//                if (remaining == len) {
                    // TODO: how about waiting for more data?
//                }
                if (!frame.endStream()) {
                    currentOffset = 0;
                    frames.remove();
                } else {
                    currentOffset += remaining;
                }
                return remaining;
            } else {
                // the user wants less than what is available
                System.arraycopy(frame.payload(), offset, b, off, len);
                currentOffset += len;
                return len;
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
}
