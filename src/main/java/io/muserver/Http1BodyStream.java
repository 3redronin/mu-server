package io.muserver;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.concurrent.atomic.AtomicReference;

@NullMarked
class Http1BodyStream extends InputStream {

    enum State {
        READING, EOF, IO_EXCEPTION, TIMED_OUT
    }

    private final Http1MessageReader parser;
    private final long maxBodySize;

    @Nullable
    private ByteBuffer bb = null;
    private boolean lastBitReceived = false;
    private long bytesReceived = 0L;

    private final AtomicReference<State> status = new AtomicReference<>(State.READING);

    Http1BodyStream(Http1MessageReader parser, long maxBodySize) {
        this.parser = parser;
        this.maxBodySize = maxBodySize;
    }

    boolean tooBig() {
        return bytesReceived > maxBodySize;
    }

    long bytesReceived() {
        return bytesReceived;
    }

    State state() {
        return status.get();
    }

    @Override
    public int read() throws IOException {
        blockUntilData();
        int state = stateOrThrow();
        if (state == -1) return -1;
        return bb.get();
    }

    private int stateOrThrow() throws IOException {
        if (tooBig()) throw new HttpException(HttpStatus.CONTENT_TOO_LARGE_413);
        switch (status.get()) {
            case READING:
                return 0;
            case EOF:
                return -1;
            case IO_EXCEPTION:
                throw new IOException("Read on a broken stream");
            case TIMED_OUT:
                throw HttpException.requestTimeout();
        }
        throw new IllegalStateException(status.get().toString());
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (off < 0) throw new IndexOutOfBoundsException("Negative offset");
        if (len < 0) throw new IndexOutOfBoundsException("Negative length");
        if (len > b.length - off) throw new IndexOutOfBoundsException("Length too long");
        blockUntilData();
        if (stateOrThrow() == -1) return -1;
        var bit = bb;
        var toWrite = Math.min(len, bit.remaining());
        if (toWrite > 0) {
            bit.get(b, off, toWrite);
        }
        return toWrite;
    }

    private void blockUntilData() throws IOException {
        if (status.get() == State.READING) {
            var ready = false;
            while (!ready) {
                var lastBody = bb;

                // If no body has been received, or the last bit has been consumed...
                if (lastBody == null || !lastBody.hasRemaining()) {
                    if (lastBitReceived) {
                        // ...we expect no more body bits, so it's an EOF
                        status.set(State.EOF);
                        ready = true;
                    } else {
                        // ...we expect more body, so read the next bit
                        Http1ConnectionMsg next;
                        try {
                            next = parser.readNext();
                        } catch (SocketTimeoutException ste) {
                            status.set(State.TIMED_OUT);
                            throw HttpException.requestTimeout();
                        } catch (IOException | ParseException pe) {
                            status.set(State.IO_EXCEPTION);
                            throw (pe instanceof IOException) ? (IOException) pe : new IOException("Parse error in request body", pe);
                        }
                        if (next instanceof MessageBodyBit) {
                            var mbb = (MessageBodyBit) next;
                            // we have more body data
                            lastBitReceived = mbb.isLast();
                            // this is an empty last-data message, so it is EOF time
                            if (mbb.isLast() && mbb.getLength() == 0) {
                                status.set(State.EOF);
                                bb = null;
                                ready = true;
                            } else if (mbb.getLength() > 0) {
                                bb = ByteBuffer.wrap(mbb.getBytes(), mbb.getOffset(), mbb.getLength());
                                bytesReceived += mbb.getLength();
                                ready = true;
                            }
                        } else if (next instanceof EndOfBodyBit) {
                            bb = null;
                            status.set(State.EOF);
                            ready = true;
                        } else {
                            status.set(State.IO_EXCEPTION);
                            throw new IOException("Unexpected message: " + next.getClass().getName());
                        }
                    }
                } else {
                    // the last body buffer still has remaining
                    ready = true;
                }
            }
        }
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
        if (status.compareAndSet(State.READING, State.EOF)) {
            var drained = lastBitReceived;
            while (!drained) {
                Http1ConnectionMsg last;
                try {
                    last = parser.readNext();
                } catch (IOException | ParseException e) {
                    status.set(State.IO_EXCEPTION);
                    break;
                }
                if (last instanceof MessageBodyBit) {
                    var mbb = (MessageBodyBit) last;
                    drained = mbb.isLast();
                    bytesReceived += mbb.getLength();
                    if (throwIfTooBig && tooBig()) {
                        throw new HttpException(HttpStatus.CONTENT_TOO_LARGE_413);
                    }
                } else if (last instanceof EndOfBodyBit) {
                    drained = true;
                } else {
                    status.set(State.IO_EXCEPTION);
                    break;
                }
            }
        }
        return status.get();
    }
}
