package io.muserver;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** BufferedOutputStream does not reject all writes after close on supported JDKs. */
final class CloseGuardedBufferedOutputStream extends BufferedOutputStream {
    private boolean closed;

    CloseGuardedBufferedOutputStream(OutputStream out, int size) {
        super(out, size);
    }

    private void ensureOpen() throws IOException {
        if (closed) throw new IOException("Response output stream is closed");
    }

    @Override public synchronized void write(int value) throws IOException {
        ensureOpen();
        super.write(value);
    }

    @Override public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        ensureOpen();
        super.write(bytes, offset, length);
    }

    @Override public synchronized void flush() throws IOException {
        ensureOpen();
        super.flush();
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        try {
            super.close();
        } finally {
            closed = true;
        }
    }
}
