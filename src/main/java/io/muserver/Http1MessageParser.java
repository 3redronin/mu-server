package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.Queue;

/** Stream adapter with blocking reads and bounded polling for the transport-independent decoder. */
class Http1MessageParser implements Http1MessageReader {
    private final InputStream source;
    private final Http1MessageDecoder decoder;
    private final byte[] readBuffer = new byte[8192];
    private final ByteBuffer input = ByteBuffer.wrap(readBuffer).flip();
    private boolean handedOff;
    private final Http1MessageReader.@Nullable Available asynchronousReader;

    @FunctionalInterface
    interface AvailableRead { int read(byte[] target) throws IOException; }

    Http1MessageParser(HttpMessageType type, Queue<HttpRequestTemp> requestQueue, InputStream source,
                       int maxHeadersLength, int maxUrlLength) {
        this(type, requestQueue, source, maxHeadersLength, maxUrlLength, null, null);
    }

    Http1MessageParser(HttpMessageType type, Queue<HttpRequestTemp> requestQueue, InputStream source,
                       int maxHeadersLength, int maxUrlLength, @Nullable TransportInputBuffer availableSource,
                       @Nullable AvailableRead availableRead) {
        this.source = source;
        this.decoder = new Http1MessageDecoder(type, requestQueue, maxHeadersLength, maxUrlLength);
        this.asynchronousReader = availableSource == null || availableRead == null ? null : new Http1MessageReader.Available() {
            @Override public @Nullable Http1ConnectionMsg readAvailable() throws IOException, ParseException {
                return Http1MessageParser.this.readAvailable(availableRead);
            }
            @Override public java.util.concurrent.CompletableFuture<Void> whenReadable() {
                // A bounded body turn can yield while a further event remains in parser storage.
                return input.hasRemaining() ? java.util.concurrent.CompletableFuture.completedFuture(null) : availableSource.whenReadable();
            }
            @Override public long readTimeoutMillis() { return availableSource.readTimeoutMillis(); }
        };
    }

    @Override public Http1MessageReader.@Nullable Available asynchronousReader() { return asynchronousReader; }

    @Override
    public Http1ConnectionMsg readNext() throws IOException, ParseException {
        checkOwnership();
        try {
            for (;;) {
                Http1ConnectionMsg next = decoder.decode(input);
                if (next != null) return next;
                input.clear();
                int read = source.read(readBuffer);
                input.limit(Math.max(0, read));
                if (read == -1) return decoder.endOfInput();
            }
        } catch (IOException | ParseException | HttpException | IllegalArgumentException e) {
            decoder.fail();
            throw e;
        }
    }

    /**
     * Decode buffered input, then attempt at most one nonblocking source read. The supplied reader
     * must consume the same source as readNext. Ownership passes between the transport and active
     * exchange, never concurrently. A turn consumes at most two read-buffer lengths.
     */
    @Nullable Http1ConnectionMsg readAvailable(AvailableRead read) throws IOException, ParseException {
        checkOwnership();
        try {
            Http1ConnectionMsg next = decoder.decode(input);
            if (next != null) return next;
            input.clear();
            int count = read.read(readBuffer);
            input.limit(Math.max(0, count));
            return count == -1 ? decoder.endOfInput() : decoder.decode(input);
        } catch (IOException | ParseException | HttpException | IllegalArgumentException error) {
            decoder.fail();
            throw error;
        }
    }

    /** Transfer the full owned buffer, including its unread range, to the upgraded protocol. */
    ByteBuffer takeInputForUpgrade() {
        checkOwnership();
        handedOff = true;
        return input;
    }

    private void checkOwnership() {
        if (handedOff) throw new IllegalStateException("HTTP parser input belongs to the upgraded protocol");
    }

    @Override
    public @Nullable FieldBlock takeTrailers() {
        return decoder.takeTrailers();
    }

    HttpRequestTemp rejectInvalidRequest(ParseException failure) {
        return decoder.rejectInvalidRequest(failure);
    }

    @Override
    public String toString() {
        return decoder.toString();
    }
}
