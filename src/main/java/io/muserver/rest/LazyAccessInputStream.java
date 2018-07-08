package io.muserver.rest;

import io.muserver.MuRequest;

import java.io.IOException;
import java.io.InputStream;

/**
 * An input stream based on the request input stream, but if no methods are called then the output stream is never created.
 */
class LazyAccessInputStream extends InputStream {

    private final MuRequest request;
    private InputStream inputStream;

    LazyAccessInputStream(MuRequest request) {
        this.request = request;
    }

    private InputStream in() {
        if (inputStream == null) {
            inputStream = request.inputStream().orElse(EmptyInputStream.INSTANCE);
        }
        return inputStream;
    }

    @Override
    public int read() throws IOException {
        return in().read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        return in().read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return in().read(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {
        return in().skip(n);
    }

    @Override
    public int available() throws IOException {
        return in().available();
    }

    @Override
    public void close() throws IOException {
        in().close();
    }

    @Override
    public synchronized void mark(int readlimit) {
        in().mark(readlimit);
    }

    @Override
    public synchronized void reset() throws IOException {
        in().reset();
    }

    @Override
    public boolean markSupported() {
        return in().markSupported();
    }
}
