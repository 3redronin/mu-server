package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;

class RequestBodyListenerToInputStreamAdapter extends InputStream implements RequestBodyListener {

    ByteBuffer curBuffer;
    DoneCallback doneCallback;
    private boolean eos = false;
    private IOException error;
    private final Object lock = new Object();
    private boolean userClosed = false;

    @Override
    public void onDataReceived(ByteBuffer buffer, DoneCallback doneCallback) throws Exception {
        synchronized (lock) {
            if (userClosed) {
                log.info("User has closed input stream");
                doneCallback.onComplete(new IOException("User already closed the input stream"));
                return;
            }
            this.curBuffer = buffer;
            this.doneCallback = doneCallback;
            lock.notify();
        }
    }

    @Override
    public void onComplete() {
        synchronized (lock) {
            eos = true;
            lock.notify();
        }
    }

    @Override
    public int available() throws IOException {
        synchronized (lock) {
            return !eos && !userClosed && error == null && curBuffer == null ? 0 : curBuffer.remaining();
        }
    }


    @Override
    public void onError(Throwable t) {
        synchronized (lock) {
            if (t instanceof IOException ioe) {
                this.error = ioe;
            } else if (t instanceof UncheckedIOException uioe) {
                this.error = uioe.getCause();
            } else {
                this.error = new IOException("Error reading data", t);
            } // todo: what about interrupted ones? and timeouts?
            lock.notify();
        }
    }

    @Override
    public int read() throws IOException {
        synchronized (lock) {
            if (eos) return -1;
            if (available() > 0) {
                return curBuffer.get();
            }
        }
        byte[] tmp = new byte[1];
        int read = read(tmp, 0, 1);
        if (read == -1) return -1;
        if (read == 0) {
            throw new IOException("Could not read single byte");
        }
        return tmp[0];
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        synchronized (lock) {
            if (eos) return -1;
            if (error != null) throw error;
            if (userClosed) throw new IOException("Stream was already closed");
            if (curBuffer != null && curBuffer.hasRemaining()) {
                int num = Math.min(len, curBuffer.remaining());
                curBuffer.get(b, off, num);
                return num;
            } else {
                try {
                    DoneCallback dc = doneCallback;
                    if (dc != null) {
                        doneCallback = null;
                        dc.onComplete(null);
                        if (error != null) {
                            throw error;
                        }
                    }
                    if (!eos && (curBuffer == null || !curBuffer.hasRemaining())) {
                        lock.wait(); // no need for timeout as the request body listener will time out and notify
                    }
                } catch (Exception e) {
                    if (error == null) {
                        onError(e);
                    }
                    throw e instanceof IOException ? (IOException) e : new IOException("Error waiting for data", e);
                }
            }
        }
        return read(b, off, len);
    }

    private static final Logger log = LoggerFactory.getLogger(RequestBodyListenerToInputStreamAdapter.class);

    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (!userClosed) {
                userClosed = true;
                DoneCallback dc = doneCallback;
                log.info("Closing req inpu str with dc=" + dc);
                if (dc != null) {
                    doneCallback = null;
                    dc.onComplete(error);
                }
            }
        }
    }
}
