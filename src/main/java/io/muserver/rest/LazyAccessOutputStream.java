package io.muserver.rest;

import io.muserver.MuResponse;

import java.io.IOException;
import java.io.OutputStream;

/**
 * An output stream based on the request output stream, but if no methods are called then the output stream is never created.
 */
class LazyAccessOutputStream extends OutputStream {
    private final MuResponse muResponse;

    LazyAccessOutputStream(MuResponse muResponse) {
        this.muResponse = muResponse;
    }

    @Override
    public void write(int b) throws IOException {
        muResponse.outputStream().write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        muResponse.outputStream().write(b, off, len);
    }

    @Override
    public void flush() throws IOException {
        muResponse.outputStream().flush();
    }

    @Override
    public void close() throws IOException {
        muResponse.outputStream().close();
    }
}
