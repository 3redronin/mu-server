package io.muserver;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

class HttpConnectionInputStream extends FilterInputStream {
    private final BaseHttpConnection httpConnection;

    public HttpConnectionInputStream(BaseHttpConnection httpConnection, InputStream in) {
        super(in);
        this.httpConnection = httpConnection;
    }

    @Override
    public int read() throws IOException {
        if (httpConnection.isClosed()) throw new IOException("The connection is closed");
        int read;
        try {
            read = in.read();
        } catch (IOException failure) {
            httpConnection.onTransportInputFailure(failure);
            throw failure;
        }
        if (read != -1) {
            httpConnection.onBytesRead(1);
        } else {
            httpConnection.onTransportInputEnd();
        }
        return read;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (httpConnection.isClosed()) throw new IOException("The connection is closed");
        int read;
        try {
            read = in.read(b, off, len);
        } catch (IOException failure) {
            httpConnection.onTransportInputFailure(failure);
            throw failure;
        }
        if (read > 0) {
            httpConnection.onBytesRead(read);
        } else if (read == -1) {
            httpConnection.onTransportInputEnd();
        }
        return read;
    }
}
