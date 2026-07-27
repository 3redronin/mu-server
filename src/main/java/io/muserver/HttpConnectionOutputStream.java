package io.muserver;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Owns successful socket-write accounting for a connection.
 */
class HttpConnectionOutputStream extends FilterOutputStream {
    private final BaseHttpConnection httpConnection;

    HttpConnectionOutputStream(
        BaseHttpConnection httpConnection,
        OutputStream out
    ) {
        super(out);
        this.httpConnection = httpConnection;
    }

    @Override
    public void write(int b) throws IOException {
        try {
            out.write(b);
        } catch (IOException failure) {
            httpConnection.onTransportOutputFailure(failure);
            throw failure;
        }
        httpConnection.onBytesSent(1);
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        try {
            out.write(b, off, len);
        } catch (IOException failure) {
            httpConnection.onTransportOutputFailure(failure);
            throw failure;
        }
        if (len > 0) {
            httpConnection.onBytesSent(len);
        }
    }

    @Override
    public void flush() throws IOException {
        try {
            out.flush();
        } catch (IOException failure) {
            httpConnection.onTransportOutputFailure(failure);
            throw failure;
        }
    }
}
