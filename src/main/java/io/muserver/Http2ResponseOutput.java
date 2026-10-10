package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

/** Keeps HPACK on the coordinator while capturing synchronous response encoders' DATA output. */
final class Http2ResponseOutput implements ResponseOutputCapture {
    private final Http2Stream stream;
    private final Path directory;
    private @Nullable Rendered rendering;
    private boolean discarding;

    Http2ResponseOutput(Http2Stream stream, Path directory) {
        this.stream = stream;
        this.directory = directory;
    }

    void write(LogicalHttp2Frame frame) throws IOException, InterruptedException {
        if (discarding) return;
        Rendered capture = rendering;
        if (capture == null) { stream.blockingWrite(frame); return; }
        if (frame instanceof Http2ResponseFrame) capture.headers.add(((Http2ResponseFrame) frame).headers());
        else if (frame instanceof Http2HeadersFrame) {
            if (capture.bytes.length() > 0 || capture.endStream || capture.headersEndStream) {
                throw new IllegalStateException("Response headers cannot follow its captured body");
            }
            capture.headers.add((Http2HeadersFrame) frame);
            capture.headersEndStream = frame.endStream();
            return;
        }
        if (!(frame instanceof Http2DataFrame)) throw new IllegalArgumentException("Unexpected response frame");
        Http2DataFrame data = (Http2DataFrame) frame;
        writeData(data.payload(), data.payloadOffset(), data.payloadLength());
        if (data.endStream()) capture.endStream = true;
    }

    void writeData(byte[] bytes, int offset, int length) throws IOException, InterruptedException {
        if (discarding) return;
        Rendered capture = rendering;
        if (capture == null) stream.blockingWriteData(bytes, offset, length);
        else {
            if (capture.endStream || capture.headersEndStream) throw new IOException("Response output is closed");
            capture.bytes.write(bytes, offset, length);
        }
    }

    void endStream() throws IOException, InterruptedException { write(Http2DataFrame.eos(stream.id)); }

    void discard() {
        if (rendering != null) throw new IllegalStateException("Response rendering is still active");
        discarding = true;
    }

    @Override public Capture begin() {
        if (discarding) throw new IllegalStateException("Response output has been discarded");
        if (rendering != null) throw new IllegalStateException("Response rendering is already active");
        Rendered capture = new Rendered();
        rendering = capture;
        return capture;
    }

    private final class Rendered implements Capture {
        private final Queue<Http2HeadersFrame> headers = new ArrayDeque<>();
        private final RenderedResponseBytes bytes = new RenderedResponseBytes(directory);
        private boolean endStream;
        private boolean headersEndStream;

        @Override public void finishRendering() throws IOException {
            rendering = null;
            bytes.seal();
        }

        @Override public @Nullable CompletableFuture<@Nullable Void> writeNext() throws IOException {
            Http2HeadersFrame header = headers.poll();
            if (header != null) return stream.writeAsynchronously(header);
            ByteBuffer next = bytes.next();
            if (next != null) {
                boolean last = endStream && !bytes.hasRemaining();
                if (last) endStream = false;
                return stream.writeAsynchronously(new Http2DataFrame(stream.id, last, next.array(),
                    next.arrayOffset() + next.position(), next.remaining()));
            }
            if (endStream) {
                endStream = false;
                return stream.writeAsynchronously(Http2DataFrame.eos(stream.id));
            }
            return null;
        }

        @Override public void close() throws IOException { headers.clear(); bytes.close(); }
    }
}
