package io.muserver;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;

class OutputStreamToByteChannelAdapter extends OutputStream {

    private final WritableByteChannel channel;
    boolean isClosed = false;

    OutputStreamToByteChannelAdapter(WritableByteChannel channel) {
        this.channel = channel;
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
        channel.write(ByteBuffer.wrap(b, off, len));
    }

    public void close() {
        isClosed = true;
    }

}
