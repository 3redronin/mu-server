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
import java.util.Arrays;
import java.util.Objects;

/** Captured output from one synchronous turn, drained later without a network wait. */
final class RenderedResponseBytes extends OutputStream {
    static final int MEMORY_LIMIT = 32768;
    static final int DRAIN_BYTES = 16_384;
    private final Path directory;
    private byte @Nullable [] memory = new byte[256];
    private @Nullable FileChannel file;
    private @Nullable Path temporaryFile;
    private byte @Nullable [] scratch;
    private long length;
    private long read;
    private @Nullable IOException writeFailure;
    private boolean sealed;
    private boolean closed;
    private byte @Nullable [] borrowCandidate;
    private int borrowOffset;
    private int borrowLength;
    private boolean borrowed;

    RenderedResponseBytes(Path directory) { this.directory = directory; }

    /** Only the unencoded response path may offer a renderer-owned, stable input range. */
    void borrowSourceIfUnchanged(byte[] source, int offset, int count) throws IOException {
        Objects.checkFromIndexSize(offset, count, source.length);
        if (closed || sealed) throw new IOException("Rendered response output is no longer writable");
        if (count > DRAIN_BYTES) throw new IllegalArgumentException("Borrow exceeds one render turn");
        // Stream initialization may already have captured headers or a buffered prefix.
        if (length != 0 || count == 0) return;
        borrowCandidate = source;
        borrowOffset = offset;
        borrowLength = count;
    }

    @Override public void write(int value) throws IOException {
        write(new byte[]{(byte) value}, 0, 1);
    }

    @Override public void write(byte[] bytes, int offset, int count) throws IOException {
        Objects.checkFromIndexSize(offset, count, bytes.length);
        if (closed || sealed) throw new IOException("Rendered response output is no longer writable");
        if (writeFailure != null) throw writeFailure;
        if (count == 0) return;
        try {
            @SuppressWarnings("ReferenceEquality") // Only the renderer-owned array is safe to borrow; equal contents are insufficient.
            boolean sameSource = bytes == borrowCandidate;
            if (length == 0 && sameSource && offset == borrowOffset && count == borrowLength) {
                borrowed = true;
                length = count;
                return;
            }
            // A second write changes this into mixed output. Materialize the first write before
            // appending or spilling, while the renderer still owns the stable source. No borrow
            // has reached the transport yet: capture drain starts only after sealing.
            if (borrowed) {
                byte[] buffered = Objects.requireNonNull(memory);
                if (length > buffered.length) memory = buffered = new byte[(int) length];
                System.arraycopy(Objects.requireNonNull(borrowCandidate), borrowOffset, buffered, 0, (int) length);
                borrowed = false;
            }
            borrowCandidate = null;
            if (file == null && count <= MEMORY_LIMIT - length) {
                byte[] buffered = Objects.requireNonNull(memory);
                int required = (int) length + count;
                if (required > buffered.length) {
                    // Generic stream growth can overshoot the bound after an uneven encoder burst.
                    buffered = Arrays.copyOf(buffered,
                        Math.min(MEMORY_LIMIT, Math.max(required, buffered.length * 2)));
                    memory = buffered;
                }
                System.arraycopy(bytes, offset, buffered, (int) length, count);
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
            writeFully(opened, ByteBuffer.wrap(Objects.requireNonNull(memory), 0, (int) length));
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
        if (!borrowed) borrowCandidate = null;
        sealed = true;
    }

    /** The returned storage is borrowed until the next read or close; callers wait for write ACK. */
    @Nullable ByteBuffer next() throws IOException {
        if (closed || !sealed) throw new IOException("Rendered response output is not readable");
        int count = (int) Math.min(DRAIN_BYTES, length - read);
        if (count == 0) return null;
        ByteBuffer result;
        FileChannel source = file;
        if (borrowed) {
            result = ByteBuffer.wrap(Objects.requireNonNull(borrowCandidate), borrowOffset + (int) read, count).slice();
        } else if (source == null) {
            result = ByteBuffer.wrap(Objects.requireNonNull(memory), (int) read, count).slice();
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
        borrowCandidate = null;
        borrowed = false;
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
