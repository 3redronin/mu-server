package io.muserver.rest;

import io.muserver.EmptyInputStream;
import io.muserver.MuRequest;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An input stream based on the request input stream, but if no methods are called then the output stream is never created.
 */
class LazyAccessInputStream extends InputStream {

    private final MuRequest request;
    private final ReentrantLock markResetLock = new ReentrantLock();
    private @Nullable InputStream inputStream;

    LazyAccessInputStream(MuRequest request) {
        this.request = request;
    }

    private InputStream in() {
        if (inputStream == null) {
            inputStream = request.inputStream().orElse(EmptyInputStream.INSTANCE);
        }
        return java.util.Objects.requireNonNull(inputStream);
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
        if (inputStream != null) {
            inputStream.close();
        }
    }

    @Override
    @SuppressWarnings("UnsynchronizedOverridesSynchronized") // ReentrantLock replaces the monitor across delegated I/O.
    public void mark(int readlimit) {
        markResetLock.lock();
        try {
            in().mark(readlimit);
        } finally {
            markResetLock.unlock();
        }
    }

    @Override
    @SuppressWarnings("UnsynchronizedOverridesSynchronized") // ReentrantLock replaces the monitor across delegated I/O.
    public void reset() throws IOException {
        markResetLock.lock();
        try {
            in().reset();
        } finally {
            markResetLock.unlock();
        }
    }

    @Override
    public boolean markSupported() {
        return in().markSupported();
    }
}
