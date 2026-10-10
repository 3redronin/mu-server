package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/** Owned output from one synchronous encoder turn, drained later without a network wait. */
final class RenderedResponseBytes extends OutputStream {
    static final int MEMORY_LIMIT = 32768;
    static final int DRAIN_BYTES = 8192;
    private final Path directory;
    private @Nullable NiceByteArrayOutputStream memory = new NiceByteArrayOutputStream(256);
    private @Nullable FileChannel file;
    private @Nullable Path temporaryFile;
    private byte @Nullable [] scratch;
    private long length;
    private long read;
    private @Nullable IOException writeFailure;
    private boolean sealed;
    private boolean closed;

    RenderedResponseBytes(Path directory) { this.directory = directory; }

    @Override public void write(int value) throws IOException {
        write(new byte[]{(byte) value}, 0, 1);
    }

    @Override public void write(byte[] bytes, int offset, int count) throws IOException {
        Objects.checkFromIndexSize(offset, count, bytes.length);
        if (closed || sealed) throw new IOException("Rendered response output is no longer writable");
        if (writeFailure != null) throw writeFailure;
        if (count == 0) return;
        try {
            if (file == null && count <= MEMORY_LIMIT - length) {
                Objects.requireNonNull(memory).write(bytes, offset, count);
            } else {
                FileChannel output = file;
                if (output == null) output = spill();
                writeFully(output, ByteBuffer.wrap(bytes, offset, count));
            }
            length += count;
        } catch (IOException failure) {
            writeFailure = failure;
            throw failure;
        }
    }

    private FileChannel spill() throws IOException {
        Path path = Files.createTempFile(directory, "mu-response-", ".tmp");
        FileChannel opened = null;
        try {
            opened = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE,
                StandardOpenOption.DELETE_ON_CLOSE);
            NiceByteArrayOutputStream buffered = Objects.requireNonNull(memory);
            writeFully(opened, ByteBuffer.wrap(buffered.rawBuffer(), 0, buffered.size()));
            file = opened;
            temporaryFile = path;
            memory = null;
            return opened;
        } catch (IOException | RuntimeException | Error failure) {
            if (opened != null) {
                try { opened.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            try { Files.deleteIfExists(path); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static void writeFully(FileChannel output, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            if (output.write(bytes) == 0) throw new IOException("Response temporary file made no write progress");
        }
    }

    void seal() throws IOException {
        if (closed) throw new IOException("Rendered response output is closed");
        if (sealed) return;
        FileChannel source = file;
        if (source != null) source.position(0);
        sealed = true;
    }

    /** The returned storage is borrowed until the next read or close; callers wait for write ACK. */
    @Nullable ByteBuffer next() throws IOException {
        if (closed || !sealed) throw new IOException("Rendered response output is not readable");
        int count = (int) Math.min(DRAIN_BYTES, length - read);
        if (count == 0) return null;
        ByteBuffer result;
        FileChannel source = file;
        if (source == null) {
            result = ByteBuffer.wrap(Objects.requireNonNull(memory).rawBuffer(), (int) read, count).slice();
        } else {
            if (scratch == null) scratch = new byte[DRAIN_BYTES];
            result = ByteBuffer.wrap(scratch, 0, count);
            while (result.hasRemaining()) {
                int received = source.read(result);
                if (received < 0) throw new EOFException("Incomplete response temporary file");
                if (received == 0) throw new IOException("Response temporary file made no read progress");
            }
            result.flip();
        }
        read += count;
        return result;
    }

    long length() { return length; }
    boolean hasRemaining() { return read < length; }
    boolean spilled() { return file != null; }

    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        memory = null;
        scratch = null;
        FileChannel source = file;
        file = null;
        Path path = temporaryFile;
        temporaryFile = null;
        Throwable failedClose = null;
        try {
            if (source != null) source.close();
        } catch (IOException | RuntimeException | Error failure) {
            failedClose = failure;
            throw failure;
        } finally {
            if (path != null) {
                try { Files.deleteIfExists(path); }
                catch (IOException cleanup) {
                    if (failedClose == null) throw cleanup;
                    failedClose.addSuppressed(cleanup);
                }
            }
        }
    }
}
