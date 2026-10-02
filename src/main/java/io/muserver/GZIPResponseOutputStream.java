package io.muserver;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.zip.GZIPOutputStream;

/** Keeps blocking response writes outside Java monitors, including on Java 21. */
final class GZIPResponseOutputStream extends GZIPOutputStream {
    private final ReentrantLock lock = new ReentrantLock();

    GZIPResponseOutputStream(OutputStream output, int bufferSize) throws IOException {
        super(output, bufferSize, true);
    }

    @Override
    @SuppressWarnings("UnsynchronizedOverridesSynchronized") // ReentrantLock replaces the JDK monitor.
    public void write(byte[] bytes, int offset, int length) throws IOException {
        lock.lock();
        try {
            // Calling super.write would re-enter GZIPOutputStream's synchronized method.
            // Feed the inherited deflater directly; the JDK still supplies framing and cleanup.
            if (def.finished()) throw new IOException("write beyond end of stream");
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) return;
            def.setInput(bytes, offset, length);
            while (!def.needsInput()) {
                deflate();
            }
            crc.update(bytes, offset, length);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void flush() throws IOException {
        lock.lock();
        try {
            super.flush();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void finish() throws IOException {
        lock.lock();
        try {
            super.finish();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            // DeflaterOutputStream.close calls finish(), so the lock must be reentrant.
            super.close();
        } finally {
            lock.unlock();
        }
    }
}
