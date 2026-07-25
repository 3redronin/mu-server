package io.muserver.rest;

import io.muserver.MuResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * An output stream based on the request output stream, but if no methods are called then the output stream is never created.
 */
class LazyAccessOutputStream extends OutputStream {
    private final MuResponse muResponse;
    private final Runnable beforeFirstWrite;
    private OutputStream os;
    private boolean prepared;

    private OutputStream out() {
        if (os == null) {
            prepare();
            os = muResponse.outputStream();
        }
        return os;
    }

    LazyAccessOutputStream(MuResponse muResponse, Runnable beforeFirstWrite) {
        this.muResponse = muResponse;
        this.beforeFirstWrite = beforeFirstWrite;
    }

    void prepare() {
        if (!prepared) {
            beforeFirstWrite.run();
            prepared = true;
        }
    }

    @Override
    public void write(int b) throws IOException {
        out().write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len > 0) {
            out().write(b, off, len);
        }
    }

    @Override
    public void flush() throws IOException {
        if (os != null) {
            os.flush();
        }
    }

    @Override
    public void close() throws IOException {
        if (os != null) {
            os.close();
            os = null;
        }
    }
}
