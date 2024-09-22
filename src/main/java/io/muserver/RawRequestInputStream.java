package io.muserver;

import org.jetbrains.annotations.NotNull;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

class RawRequestInputStream extends FilterInputStream {

    private final Mu3Http1Connection connection;

    protected RawRequestInputStream(@NotNull Mu3Http1Connection connection, @NotNull InputStream in) {
        super(in);
        this.connection = connection;
    }

    @Override
    public int read() throws IOException {
        if (connection.isClosed()) throw new IOException("The connection is closed");
        try {
            int read = in.read();
            if (read >= 0) {
                connection.onByteRead(read);
            }
            return read;
        } catch (SocketTimeoutException e) {
            throw new HttpException(HttpStatus.REQUEST_TIMEOUT_408);
        }
    }
    @Override
    public int read(byte[] b) throws IOException {
        try {
            return read(b, 0, b.length);
        } catch (SocketTimeoutException e) {
            throw new HttpException(HttpStatus.REQUEST_TIMEOUT_408);
        }
    }
    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (connection.isClosed()) throw new IOException("The connection is closed");
        try {
            int read = in.read(b, off, len);
            if (read >= 0) {
                connection.onBytesRead(b, off, read);
            }
            return read;
        } catch (SocketTimeoutException e) {
            throw new HttpException(HttpStatus.REQUEST_TIMEOUT_408);
        }
    }

}
