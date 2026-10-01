package io.muserver;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** Protects a custom encoder's outer stream, which may have its own buffer. */
final class CloseGuardedOutputStream extends FilterOutputStream {
    private volatile boolean closed;

    CloseGuardedOutputStream(OutputStream out) {
        super(out);
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Response output stream is closed");
    }

    @Override public void write(int value) throws IOException {
        ensureOpen();
        out.write(value);
    }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        ensureOpen();
        out.write(bytes, offset, length);
    }

    @Override public void write(byte[] bytes) throws IOException {
        write(bytes, 0, bytes.length);
    }

    @Override public void flush() throws IOException {
        ensureOpen();
        out.flush();
    }

    @Override public void close() throws IOException {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        out.close();
    }
}
