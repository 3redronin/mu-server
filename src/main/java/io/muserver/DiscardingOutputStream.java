package io.muserver;

import org.jspecify.annotations.NullMarked;

import java.io.IOException;
import java.io.OutputStream;

@NullMarked
class DiscardingOutputStream extends OutputStream {
    private DiscardingOutputStream() {}
    public static final DiscardingOutputStream INSTANCE = new DiscardingOutputStream();

    @Override
    public void write(int b) throws IOException {
    }

    @Override
    public void write(byte[] b) throws IOException {
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
    }

}
