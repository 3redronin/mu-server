package io.muserver;

import org.jetbrains.annotations.NotNull;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

class HttpConnectionInputStream extends FilterInputStream {
    private final BaseHttpConnection httpConnection;

    public HttpConnectionInputStream(@NotNull BaseHttpConnection httpConnection, @NotNull InputStream in) {
        super(in);
        this.httpConnection = httpConnection;
    }

    @Override
    public int read() throws IOException {
        if (httpConnection.isClosed()) throw new IOException("The connection is closed");
        int read = in.read();
        if (read != -1) {
            httpConnection.onBytesRead(1);
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
        int read = in.read(b, off, len);
        if (read != -1) {
            httpConnection.onBytesRead(read);
        }
        return read;
    }
}
