package io.muserver;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

class FixedSizeOutputStream extends OutputStream {
    private final long declaredLen;
    private final OutputStream out;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);
    private long bytesWritten = 0L;

    public FixedSizeOutputStream(long declaredLen, OutputStream out) {
        this.declaredLen = declaredLen;
        this.out = out;
    }

    @Override
    public void write(int b) throws IOException {
        bytesWritten++;
        throwIfOver();
        out.write(b);
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (len > 0) {
            bytesWritten += len;
            throwIfOver();
            out.write(b, off, len);
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
            if (bytesWritten != declaredLen) {
                throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR_500, "Fixed size body expected $declaredLen bytes but had $bytesWritten written");
            }
            out.flush();
        }
    }

    private void throwIfOver() {
        if (bytesWritten > declaredLen) {
            throw new HttpException(HttpStatus.INTERNAL_SERVER_ERROR_500, "Fixed size body size of " + declaredLen + " exceeded");
        }
    }
}

