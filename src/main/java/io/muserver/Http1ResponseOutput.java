package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** The response's ordinary stream and encoder always write through this same interception point. */
final class Http1ResponseOutput extends OutputStream implements ResponseOutputCapture {
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

    @Override public void write(int value) throws IOException {
        if (discarding) return;
        Rendered capture = rendering;
        if (capture == null) blocking.write(value);
        else capture.bytes.write(value);
    }

    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
        if (discarding) return;
        Rendered capture = rendering;
        if (capture == null) blocking.write(bytes, offset, length);
        else capture.bytes.write(bytes, offset, length);
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

        private Rendered(AsyncTransportOutput writer) { this.writer = writer; }

        @Override public void borrowSourceIfUnchanged(byte[] source, int offset, int length) throws IOException {
            if (sourceBorrowingAllowed) bytes.borrowSourceIfUnchanged(source, offset, length);
        }

        @Override public void finishRendering() throws IOException {
            rendering = null;
            bytes.seal();
        }

        @Override public @Nullable CompletableFuture<@Nullable Void> writeNext() throws IOException {
            ByteBuffer next = multiFrameBorrowAllowed() ? bytes.next(ResponseWriteSizes.MAX_BORROW_BYTES) : bytes.next();
            if (next != null) return writer.write(next);
            if (flushRequested && !flushSent) {
                flushSent = true;
                return writer.write(ByteBuffer.allocate(0));
            }
            return null;
        }

        @Override public void close() throws IOException { bytes.close(); }
    }
}
