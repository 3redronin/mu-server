package io.muserver.rest;

import io.muserver.MuResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * An output stream based on the request output stream, but if no methods are called then the output stream is never created.
 */
class LazyAccessOutputStream extends OutputStream {
    // Enough for wrappers such as GZIPOutputStream to write their preamble without committing the response.
    static final int MAX_DEFERRED_BYTES = 8192;
    private final MuResponse muResponse;
    private final Runnable beforeFirstWrite;
    private OutputStream os;
    private boolean liveOutputAccessed;
    private boolean prepared;
    private boolean writesReleased;
    private boolean writesDiscarded;
    private ByteArrayOutputStream deferredBytes = new ByteArrayOutputStream();

    private OutputStream liveOutputStream() throws IOException {
        if (os == null) {
            prepare();
            os = muResponse.outputStream();
            liveOutputAccessed = true;
            deferredBytes.writeTo(os);
            deferredBytes = null;
        }
        return os;
    }

    LazyAccessOutputStream(MuResponse muResponse, Runnable beforeFirstWrite) {
        this.muResponse = muResponse;
        this.beforeFirstWrite = beforeFirstWrite;
    }

    void releaseWrites() {
        writesReleased = true;
    }

    void discardUncommittedWrites() {
        if (!liveOutputAccessed) {
            deferredBytes.reset();
            writesDiscarded = true;
        }
    }

    boolean hasDeferredBytes() {
        return deferredBytes != null && deferredBytes.size() > 0;
    }

    boolean hasWrittenBytes() {
        return liveOutputAccessed || hasDeferredBytes();
    }

    void finish() throws IOException {
        releaseWrites();
        if (hasDeferredBytes()) {
            liveOutputStream();
        }
    }

    void prepare() {
        if (!prepared) {
            beforeFirstWrite.run();
            prepared = true;
        }
    }

    @Override
    public void write(int b) throws IOException {
        if (writesDiscarded) {
            return;
        }
        if (!writesReleased && deferredBytes.size() < MAX_DEFERRED_BYTES) {
            deferredBytes.write(b);
        } else {
            releaseWrites();
            liveOutputStream().write(b);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0 || writesDiscarded) {
            return;
        }
        if (!writesReleased && len <= MAX_DEFERRED_BYTES - deferredBytes.size()) {
            deferredBytes.write(b, off, len);
        } else {
            releaseWrites();
            liveOutputStream().write(b, off, len);
        }
    }

    @Override
    public void flush() throws IOException {
        if (writesReleased && hasDeferredBytes()) {
            liveOutputStream();
        }
        if (os != null) {
            os.flush();
        }
    }

    @Override
    public void close() throws IOException {
        if (writesReleased && hasDeferredBytes()) {
            liveOutputStream();
        }
        if (os != null) {
            os.close();
            os = null;
        }
    }
}
