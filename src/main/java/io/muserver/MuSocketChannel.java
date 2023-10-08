package io.muserver;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;

interface MuSocketChannel {

    void read(CompletionHandler<Integer, Void> completionHandler);

    void scatteringWrite(ByteBuffer[] srcs, int offset, int length, CompletionHandler<Long, Void> handler);

    ByteBuffer readBuffer();

    boolean isOpen();

    void close(DoneCallback callback);

    void abort() throws IOException;

}

