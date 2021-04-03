package io.muserver.rest;

import io.muserver.MuResponse;

import java.io.IOException;
import java.io.OutputStream;

/**
 * An output stream based on the request output stream, but if no methods are called then the output stream is never created.
 */
class LazyAccessOutputStream extends OutputStream {
    private final MuResponse muResponse;
    private OutputStream os;

    private OutputStream out() {
        if (os == null) {
            os = muResponse.outputStream();
        }
        return os;
    }

    LazyAccessOutputStream(MuResponse muResponse) {
        this.muResponse = muResponse;
    }

    @Override
    public void write(int b) throws IOException {
        out().write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out().write(b, off, len);
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
