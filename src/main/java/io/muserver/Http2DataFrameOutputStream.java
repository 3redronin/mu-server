package io.muserver;


import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

class Http2DataFrameOutputStream extends OutputStream {

    private final Http2Stream stream;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    Http2DataFrameOutputStream(Http2Stream stream) {
        this.stream = stream;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[] { (byte)b }, 0, 1);
    }

    /**
     * Blockingly writes a data frame to the http2 connection - flushing does nothing. So this
     * is designed to be wrapped by a buffered output stream
     */
    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (!closed.get()) {
            try {
                stream.blockingWriteData(b, off, len);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while writing data frame");
            }
        }
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            try {
                stream.blockingWrite(Http2DataFrame.eos(stream.id));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while writing data frame");
            }
        }
    }
}
