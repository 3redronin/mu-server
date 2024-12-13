package io.muserver;


import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

@NullMarked
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

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (!closed.get()) {
            try {
                stream.blockingWrite(new Http2DataFrame(stream.id, false, b, off, len));
            } catch (InterruptedException e) {
                throw new InterruptedIOException("Interrupted while writing data frame");
            }
        }
    }

    @Override
    public void flush() throws IOException {
        stream.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            try {
                stream.blockingWrite(new Http2DataFrame(stream.id, true, new byte[0], 0, 0));
                stream.flush();
            } catch (InterruptedException e) {
                throw new InterruptedIOException("Interrupted while writing data frame");
            }
        }
    }
}
