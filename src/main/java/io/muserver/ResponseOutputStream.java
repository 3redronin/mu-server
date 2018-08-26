package io.muserver;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

class ResponseOutputStream extends OutputStream {

    private final MuResponseImpl response;
    private boolean isClosed = false;

    ResponseOutputStream(MuResponseImpl response) {

        this.response = response;
    }

    @Override
    public void write(int b) throws IOException {
        write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (isClosed) {
            throw new IOException("Cannot write to closed output stream");
        }
        response.sendBodyData(b, off, len);
    }

    public void close() {
        isClosed = true;
    }

}
