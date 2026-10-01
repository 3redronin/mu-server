package io.muserver;

import java.io.IOException;
import java.io.OutputStream;

class DiscardingOutputStream extends OutputStream {
    static final DiscardingOutputStream CLOSED = new DiscardingOutputStream(true);
    private volatile boolean closed;

    DiscardingOutputStream() {}

    private DiscardingOutputStream(boolean closed) { this.closed = closed; }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Response output stream is closed");
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
    }

    @Override
    public void write(byte[] b) throws IOException {
        ensureOpen();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
    }

    @Override public void flush() throws IOException { ensureOpen(); }

    @Override public void close() { closed = true; }

}
