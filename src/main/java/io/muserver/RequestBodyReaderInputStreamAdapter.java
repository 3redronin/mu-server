package io.muserver;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;

class RequestBodyReaderInputStreamAdapter extends InputStream implements RequestBodyReader {
    private boolean receivedLast = false;
    private boolean finished = false;
    private ByteBuf currentBuf;
    private DoneCallback currentCallback;
    private boolean userClosed = false;

    @Override
    public void onRequestBodyRead(ByteBuf content, boolean last, DoneCallback callback) {
        synchronized (this) {
            if (currentBuf != null) {
                throw new IllegalStateException("Got content before the previous was completed");
            }
            if (currentCallback != null) {
                throw new IllegalStateException("Got content before the previous callback was invoked");
            }
            if (userClosed) {
                throw new IllegalStateException("Request body data received after the user closed the input stream");
            }
            this.currentBuf = content;
            this.currentCallback = callback;
            if (last) {
                receivedLast = true;
            }
            notify();
        }
    }

    @Override
    public int read() throws IOException {
        synchronized (this) {
            if (finished) {
                return -1;
            }
            while (currentBuf == null || (currentBuf.readableBytes() == 0 && !receivedLast)) {
                waitForData();
            }
            if (currentBuf.readableBytes() == 0) {
                afterConsumed();
                return -1;
            }
            byte b = currentBuf.readByte();
            afterConsumed();
            return b;
        }
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (b == null) {
            throw new NullPointerException();
        } else if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        } else if (len == 0) {
            return 0;
        }

        synchronized (this) {
            if (userClosed) {
                throw new IOException("Cannot call read after the stream is closed");
            }
            if (finished) {
                return -1;
            }
            while (currentBuf == null) {
                waitForData();
            }
            int actual = Math.min(len, currentBuf.readableBytes());
            if (actual > 0) {
                currentBuf.readBytes(b, off, actual);
            }
            afterConsumed();
            return actual;
        }
    }

    @Override
    public long skip(long n) throws IOException {
        synchronized (this) {
            waitForData();
            int toSkip = Math.min((int) n, currentBuf.readableBytes());
            currentBuf.skipBytes(toSkip);
            afterConsumed();
            return toSkip;
        }
    }

    private void afterConsumed() throws IOException {
        if (currentBuf.readableBytes() == 0) {
            currentBuf = null;
            try {
                currentCallback.onComplete(null);
                currentCallback = null;
            } catch (Exception e) {
                throw new IOException("Error completing done callback", e);
            } finally {
                if (receivedLast) {
                    finished = true;
                }
            }
        }
    }

    private void waitForData() throws InterruptedIOException {
        if (currentBuf != null) {
            return;
        }
        try {
            wait();
        } catch (InterruptedException e) {
            DoneCallback cb = this.currentCallback;
            if (cb != null) {
                try {
                    cb.onComplete(e);
                } catch (Exception ignored) { }
            }
            throw new InterruptedIOException("Timed out waiting for data");
        }
    }

    @Override
    public int available() throws IOException {
        synchronized (this) {
            return currentBuf == null ? 0 : currentBuf.readableBytes();
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            userClosed = true;
        }
    }
}
