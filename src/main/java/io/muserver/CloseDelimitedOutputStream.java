package io.muserver;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

class CloseDelimitedOutputStream extends OutputStream {
    private final OutputStream out;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    CloseDelimitedOutputStream(OutputStream out) {
        this.out = out;
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        out.write(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        ensureOpen();
        out.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        out.write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        ensureOpen();
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (isClosed.compareAndSet(false, true)) {
            out.flush();
        }
    }
    private void ensureOpen() throws IOException {
        if (isClosed.get()) throw new IOException("Response output stream is closed");
    }
}
