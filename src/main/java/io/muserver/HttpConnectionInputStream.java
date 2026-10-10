package io.muserver;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

class HttpConnectionInputStream extends FilterInputStream {
    private final BaseHttpConnection httpConnection;
    private final boolean countBytes;

    public HttpConnectionInputStream(BaseHttpConnection httpConnection, InputStream in) {
        this(httpConnection, in, true);
    }

    /** Channel transports already count plaintext on admission, but readers still report EOF/failure. */
    HttpConnectionInputStream(BaseHttpConnection httpConnection, InputStream in, boolean countBytes) {
        super(in);
        this.httpConnection = httpConnection;
        this.countBytes = countBytes;
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
            if (countBytes) httpConnection.onBytesRead(1);
        } else {
            httpConnection.onTransportInputEnd();
        }
        return read;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    /** Polling counterpart for an exclusively owned channel input; accounting stays identical. */
    int readAvailable(TransportInputBuffer input, byte[] target) throws IOException {
        if (httpConnection.isClosed()) throw new IOException("The connection is closed");
        int read;
        try { read = input.readAvailable(target); }
        catch (IOException failure) {
            httpConnection.onTransportInputFailure(failure);
            throw failure;
        }
        if (read > 0) {
            if (countBytes) httpConnection.onBytesRead(read);
        } else if (read == -1) httpConnection.onTransportInputEnd();
        return read;
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
            if (countBytes) httpConnection.onBytesRead(read);
        } else if (read == -1) {
            httpConnection.onTransportInputEnd();
        }
        return read;
    }
}
