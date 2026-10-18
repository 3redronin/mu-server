package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** The response's ordinary stream and encoder always write through this same interception point. */
final class Http1ResponseOutput extends OutputStream implements ResponseOutputCapture {
    // Stable source views need no extra owned buffer. The transport drains each view in
    // bounded writes/visits; its acknowledgement still owns the entire retained source span.
    private static final int MAX_PLAINTEXT_SOURCE_BORROW_BYTES = 1024 * 1024;
    private final OutputStream blocking;
    private final @Nullable AsyncTransportOutput asynchronous;
    private final Path directory;
    private @Nullable Rendered rendering;
    private boolean discarding;
    private boolean sourceBorrowingAllowed;
    private boolean largeSourceBorrowingAllowed;

    Http1ResponseOutput(OutputStream blocking, @Nullable AsyncTransportOutput asynchronous, Path directory) {
        this.blocking = blocking;
        this.asynchronous = asynchronous;
        this.directory = directory;
    }

    void allowSourceBorrowing(boolean allowed) { sourceBorrowingAllowed = allowed; }

    void allowLargeSourceBorrowing(boolean allowed) { largeSourceBorrowingAllowed = allowed; }

    @Override public boolean inputHeadroomUseful() { return sourceBorrowingAllowed; }

    @Override public boolean multiFrameBorrowAllowed() {
        return sourceBorrowingAllowed && largeSourceBorrowingAllowed;
    }

    @Override public int maxSourceBorrowBytes() { return MAX_PLAINTEXT_SOURCE_BORROW_BYTES; }

    @Override public void write(int value) throws IOException {
        if (discarding) return;
        Rendered capture = rendering;
        if (capture == null) blocking.write(value);
        else capture.write(new byte[]{(byte) value}, 0, 1);
    }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        if (discarding) return;
        Rendered capture = rendering;
        if (capture == null) blocking.write(bytes, offset, length);
        else capture.write(bytes, offset, length);
    }

    @Override public void flush() throws IOException {
        if (discarding) return;
        Rendered capture = rendering;
        if (capture == null) blocking.flush();
        else capture.flushRequested = true;
    }

    /** Terminal cleanup may close encoders without emitting a successful wire ending. */
    void discard() {
        if (rendering != null) throw new IllegalStateException("Response rendering is still active");
        discarding = true;
    }

    @Override public Capture begin() {
        if (discarding) throw new IllegalStateException("Response output has been discarded");
        AsyncTransportOutput writer = java.util.Objects.requireNonNull(asynchronous);
        if (rendering != null) throw new IllegalStateException("Response rendering is already active");
        Rendered capture = new Rendered(writer);
        rendering = capture;
        return capture;
    }

    private final class Rendered implements Capture {
        private final RenderedResponseBytes bytes = new RenderedResponseBytes(directory);
        private final AsyncTransportOutput writer;
        private boolean flushRequested;
        private boolean flushSent;
        private boolean sealed;
        private @Nullable IOException writeFailure;
        private byte @Nullable [] candidate;
        private int candidateOffset;
        private int candidateLength;
        private @Nullable ByteBuffer borrowedTail;

        private Rendered(AsyncTransportOutput writer) { this.writer = writer; }

        @Override public void borrowSourceIfUnchanged(byte[] source, int offset, int length) throws IOException {
            ensureWritable();
            if (multiFrameBorrowAllowed()) {
                java.util.Objects.checkFromIndexSize(offset, length, source.length);
                if (length > maxSourceBorrowBytes()) throw new IllegalArgumentException("Borrow exceeds one render turn");
                candidate = source;
                candidateOffset = offset;
                candidateLength = length;
            } else if (sourceBorrowingAllowed) bytes.borrowSourceIfUnchanged(source, offset, length);
        }

        private void write(byte[] source, int offset, int length) throws IOException {
            ensureWritable();
            if (writeFailure != null) throw writeFailure;
            java.util.Objects.checkFromIndexSize(offset, length, source.length);
            if (length == 0) return;
            try { append(source, offset, length); }
            catch (IOException failure) { writeFailure = failure; throw failure; }
        }

        private void append(byte[] source, int offset, int length) throws IOException {
            ByteBuffer tail = borrowedTail;
            if (tail != null) {
                // Unexpected output after the borrowed body becomes ordinary owned output.
                // A failed append leaves bytes.length() at the prefix, so keep the stable tail
                // until the copy succeeds. Rendering failure can still drain the produced prefix.
                bytes.write(tail.array(), tail.arrayOffset() + tail.position(), tail.remaining());
                borrowedTail = null;
            }
            @SuppressWarnings("ReferenceEquality") // Only the renderer's unchanged array may be borrowed.
            boolean sameSource = source == candidate;
            if (sameSource && offset == candidateOffset && length == candidateLength && !bytes.spilled()) {
                // BufferedOutputStream can emit headers or an earlier body prefix first. Keep
                // that bounded prefix owned, but do not copy the unchanged renderer input too.
                borrowedTail = ByteBuffer.wrap(source, offset, length).slice();
                candidate = null;
            } else bytes.write(source, offset, length);
        }

        private void ensureWritable() throws IOException {
            if (sealed) throw new IOException("Response capture is no longer writable");
        }

        @Override public void finishRendering() throws IOException {
            rendering = null;
            sealed = true;
            candidate = null;
            bytes.seal();
        }

        @Override public @Nullable CompletableFuture<@Nullable Void> writeNext() throws IOException {
            ByteBuffer next = bytes.next();
            if (next != null) return writer.write(next);
            ByteBuffer tail = borrowedTail;
            if (tail != null) {
                borrowedTail = null;
                return writer.write(tail);
            }
            if (flushRequested && !flushSent) {
                flushSent = true;
                return writer.write(ByteBuffer.allocate(0));
            }
            return null;
        }

        @Override public void close() throws IOException {
            sealed = true;
            candidate = null;
            borrowedTail = null;
            bytes.close();
        }
    }
}
