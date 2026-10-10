package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.concurrent.atomic.AtomicReference;

class Http1BodyStream extends InputStream implements RequestTrailersAccessor, AsyncBodyInput.Provider {

    enum State {
        READING, DISCARDING, EOF, IO_EXCEPTION, TIMED_OUT
    }

    private final Http1MessageReader parser;
    private final long maxBodySize;
    private final Http1MessageReader.@Nullable Available availableReader;
    private final @Nullable AsyncBodyInput asynchronousInput;

    @Nullable
    private ByteBuffer bb = null;
    @Nullable
    private FieldBlock trailers = null;
    private boolean lastBitReceived = false;
    private long bytesReceived = 0L;

    private final AtomicReference<State> status = new AtomicReference<>(State.READING);

    Http1BodyStream(Http1MessageReader parser, long maxBodySize) {
        this.parser = parser;
        this.maxBodySize = maxBodySize;
        this.availableReader = parser.asynchronousReader();
        var reader = availableReader;
        this.asynchronousInput = reader == null ? null : new AsyncBodyInput() {
            @Override public int readAvailable(byte[] target) throws IOException {
                if (target.length == 0) return 0;
                boolean ready = fill(false);
                if (stateOrThrow() == -1) return -1;
                if (!ready) return 0;
                ByteBuffer bit = java.util.Objects.requireNonNull(bb);
                int count = Math.min(target.length, bit.remaining());
                bit.get(target, 0, count);
                return count;
            }
            @Override public java.util.concurrent.CompletableFuture<Void> whenReadable() {
                return reader.whenReadable();
            }
            @Override public long readTimeoutMillis() { return reader.readTimeoutMillis(); }
            @Override public RuntimeException timeoutFailure() {
                status.set(State.TIMED_OUT);
                return HttpException.requestTimeout();
            }
        };
    }

    @Override public @Nullable AsyncBodyInput asynchronousInput() { return asynchronousInput; }

    boolean tooBig() {
        return bytesReceived > maxBodySize;
    }

    long bytesReceived() {
        return bytesReceived;
    }

    State state() {
        return java.util.Objects.requireNonNull(status.get());
    }

    @Override
    public int read() throws IOException {
        blockUntilData();
        int state = stateOrThrow();
        if (state == -1) return -1;
        return java.util.Objects.requireNonNull(bb).get() & 0xff;
    }

    private int stateOrThrow() throws IOException {
        if (tooBig()) throw new HttpException(HttpStatus.CONTENT_TOO_LARGE_413);
        switch (state()) {
            case READING:
                return 0;
            case EOF:
                return -1;
            case IO_EXCEPTION:
                throw new IOException("Read on a broken stream");
            case DISCARDING:
                throw new IOException("Request body is being discarded");
            case TIMED_OUT:
                throw HttpException.requestTimeout();
        }
        throw new IllegalStateException(state().toString());
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (off < 0) throw new IndexOutOfBoundsException("Negative offset");
        if (len < 0) throw new IndexOutOfBoundsException("Negative length");
        if (len > b.length - off) throw new IndexOutOfBoundsException("Length too long");
        blockUntilData();
        if (stateOrThrow() == -1) return -1;
        var bit = java.util.Objects.requireNonNull(bb);
        var toWrite = Math.min(len, bit.remaining());
        if (toWrite > 0) {
            bit.get(b, off, toWrite);
        }
        return toWrite;
    }

    private void blockUntilData() throws IOException {
        fill(true);
    }

    /** The blocking and polling views share all framing, size-limit and terminal-state rules. */
    private boolean fill(boolean wait) throws IOException {
        for (int step = 0; status.get() == State.READING; step++) {
            ByteBuffer lastBody = bb;
            if (lastBody != null && lastBody.hasRemaining()) return true;
            if (lastBitReceived) { status.set(State.EOF); return true; }
            if (!wait && step == 64) return false;
            Http1ConnectionMsg next;
            try {
                next = wait ? parser.readNext() : java.util.Objects.requireNonNull(availableReader).readAvailable();
            } catch (SocketTimeoutException ste) {
                status.set(State.TIMED_OUT);
                throw HttpException.requestTimeout();
            } catch (IOException | ParseException pe) {
                status.set(State.IO_EXCEPTION);
                throw pe instanceof IOException ? (IOException) pe : new IOException("Parse error in request body", pe);
            } catch (HttpException | IllegalArgumentException invalidBody) {
                status.set(State.IO_EXCEPTION);
                throw invalidBody;
            }
            if (next == null) return false;
            if (MessageBodyBit.isEndOfBody(next)) {
                trailers = parser.takeTrailers();
                bb = null;
                status.set(State.EOF);
            } else if (MessageBodyBit.isEof(next)) {
                status.set(State.IO_EXCEPTION);
                throw new IOException("Incomplete request body");
            } else if (next instanceof MessageBodyBit) {
                var bit = (MessageBodyBit) next;
                lastBitReceived = bit.isLast();
                if (bit.isLast() && bit.length() == 0) {
                    status.set(State.EOF);
                    bb = null;
                } else if (bit.length() > 0) {
                    bb = ByteBuffer.wrap(bit.bytes(), bit.offset(), bit.length());
                    bytesReceived += bit.length();
                    return true;
                }
            } else {
                status.set(State.IO_EXCEPTION);
                throw new IOException("Unexpected message: " + next.getClass().getName());
            }
        }
        return true;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0L) return 0L;
        var rem = n;
        while (rem > 0) {
            blockUntilData();
            stateOrThrow();
            if (bb == null) {
                break;
            }
            var s = (int)Math.min(bb.remaining(), rem);
            bb.position(bb.position() + s);
            rem -= s;
        }
        return n - rem;
    }

    @Override
    public int available() {
        var b = bb;
        return b == null ? 0 : b.remaining();
    }

    /**
     * Discards any remaining bits of this stream and closes the stream.
     * <p>This can be called multiple times</p>
     */
    State discardRemaining(boolean throwIfTooBig) {
        return discard(throwIfTooBig, true);
    }

    /** Returns DISCARDING when the exclusive cleanup owner must await more input or yield. */
    State discardAvailable(boolean throwIfTooBig) { return discard(throwIfTooBig, false); }

    void discardFailed() {
        bb = null;
        status.set(State.IO_EXCEPTION);
    }

    private State discard(boolean throwIfTooBig, boolean wait) {
        if (status.compareAndSet(State.READING, State.DISCARDING) || status.get() == State.DISCARDING) {
            bb = null;
            var drained = lastBitReceived;
            int steps = 0;
            while (!drained) {
                if (!wait && steps++ == 64) return state();
                Http1ConnectionMsg last;
                try {
                    last = wait ? parser.readNext() : java.util.Objects.requireNonNull(availableReader).readAvailable();
                } catch (IOException | ParseException | HttpException | IllegalArgumentException e) {
                    status.set(State.IO_EXCEPTION);
                    break;
                }
                if (last == null) return state();
                if (MessageBodyBit.isEndOfBody(last)) {
                    trailers = parser.takeTrailers();
                    drained = true;
                } else if (MessageBodyBit.isEof(last)) {
                    status.set(State.IO_EXCEPTION);
                    break;
                } else if (last instanceof MessageBodyBit) {
                    var mbb = (MessageBodyBit) last;
                    drained = lastBitReceived = mbb.isLast();
                    bytesReceived += mbb.length();
                    if (throwIfTooBig && tooBig()) {
                        status.set(State.IO_EXCEPTION);
                        throw new HttpException(HttpStatus.CONTENT_TOO_LARGE_413);
                    }
                } else {
                    status.set(State.IO_EXCEPTION);
                    break;
                }
            }
            bb = null;
            status.compareAndSet(State.DISCARDING, State.EOF);
        }
        return state();
    }

    @Override
    public boolean isRequestBodyComplete() {
        return status.get() == State.EOF;
    }

    @Override
    public @Nullable Headers trailers() {
        return trailers;
    }
}
