package io.muserver;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.muserver.ParseUtils.CRLF;

class ChunkedOutputStream extends OutputStream {
    private static final byte[] endChunk = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private final OutputStream out;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    ChunkedOutputStream(OutputStream out) {
        this.out = out;
    }
    @Override
    public void write(int b) throws IOException {
        // write an HTTP chunk with size=1
        var array = new byte[6];
        array[0] = 1;
        array[1] = 13;
        array[2] = 10;
        array[3] = (byte)b;
        array[4] = 13;
        array[5] = 10;
        out.write(array);
    }

    @Override
    public void write(byte[] b) throws  IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (len > 0) {
            byte[] lenBytes = Integer.toString(len, 16).getBytes(StandardCharsets.US_ASCII);
            out.write(lenBytes, 0, lenBytes.length);
            out.write(CRLF, 0 , 2);
            out.write(b, off, len);
            out.write(CRLF, 0, 2);
        }
    }

    @Override
    public void flush() throws IOException {
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (isClosed.compareAndSet(false, true)) {

            // don't actually close the underlying as it is a reusable connection

            Throwable writeException = null;
            try {
                out.write(endChunk);
            } catch (Throwable e) {
                writeException = e;
            } finally {
                if (writeException == null) {
                    out.flush();
                } else {
                    try {
                        out.flush();
                    } catch (Throwable flushException) {
                        if (flushException != writeException) {
                            flushException.addSuppressed(writeException);
                        }
                        throw flushException;
                    }
                }
            }
        }
    }


}
