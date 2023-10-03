package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.TimeUnit;

interface MuSocketChannel {

    void read(CompletionHandler<Integer, Void> completionHandler);

    void scatteringWrite(ByteBuffer[] srcs, int offset, int length, CompletionHandler<Long, Void> handler);

    ByteBuffer readBuffer();

    boolean isOpen();

    void close(DoneCallback callback);

    void abort() throws IOException;

}

class AsyncPlaintextSocketChannel implements MuSocketChannel {
    private static final Logger log = LoggerFactory.getLogger(AsyncPlaintextSocketChannel.class);

    private final ByteBuffer readBuffer;
    private final long readTimeoutMillis;
    private final long writeTimeoutMillis;
    private final AsynchronousSocketChannel channel;

    AsyncPlaintextSocketChannel(ByteBuffer readBuffer, long readTimeoutMillis, long writeTimeoutMillis, AsynchronousSocketChannel channel) {
        this.readBuffer = readBuffer;
        this.readTimeoutMillis = readTimeoutMillis;
        this.writeTimeoutMillis = writeTimeoutMillis;
        this.channel = channel;
    }

    public void read(CompletionHandler<Integer, Void> completionHandler) {
        if (readBuffer.position() > 0) {
            log.warn("********** read buffer still has stuff!! " + readBuffer.position());
        }
        channel.read(readBuffer, readTimeoutMillis, TimeUnit.MILLISECONDS, null, completionHandler);
    }

    @Override
    public void scatteringWrite(ByteBuffer[] srcs, int offset, int length, CompletionHandler<Long, Void> handler) {
        channel.write(srcs, offset, length, writeTimeoutMillis, TimeUnit.MILLISECONDS, null, handler);
    }

    @Override
    public ByteBuffer readBuffer() {
        return readBuffer;
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close(DoneCallback callback) {
        try {
            channel.shutdownOutput();
        } catch (IOException ignored) {
        }
        try {
            channel.close();
            callback.onComplete(null);
        } catch (IOException e) {
            callback.onComplete(e);
        }
    }

    @Override
    public void abort() throws IOException {
        channel.close();
    }

}
