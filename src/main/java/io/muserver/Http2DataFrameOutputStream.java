package io.muserver;


import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

class Http2DataFrameOutputStream extends OutputStream {

    private final Http2ResponseOutput output;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    Http2DataFrameOutputStream(Http2ResponseOutput output) {
        this.output = output;
    }

    @Override
    public void write(int b) throws IOException {
        ensureOpen();
        write(new byte[] { (byte)b }, 0, 1);
    }

    /**
     * Emits DATA through the response's blocking or capturing view. Flushing does nothing,
     * so this is designed to be wrapped by a buffered output stream.
     */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        ensureOpen();
        try {
            output.writeData(b, off, len);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while writing data frame");
        }
    }

    @Override public void flush() throws IOException { ensureOpen(); }

    private void ensureOpen() throws IOException {
        if (closed.get()) throw new IOException("Response output stream is closed");
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            try {
                output.endStream();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while writing data frame");
            }
        }
    }
}
