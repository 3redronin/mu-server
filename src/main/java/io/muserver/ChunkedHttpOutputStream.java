package io.muserver;

import io.netty.buffer.Unpooled;

import java.io.IOException;
import java.io.OutputStream;

class ChunkedHttpOutputStream extends OutputStream {
    private final NettyResponseAdaptor response;

    boolean isClosed = false;

    ChunkedHttpOutputStream(NettyResponseAdaptor response) {
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
        response.write(Unpooled.copiedBuffer(b, off, len), true);
    }

    public void close() {
        isClosed = true;
    }

}
