package io.muserver;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;

class DiscardingOutputStream extends OutputStream {
    private DiscardingOutputStream() {}
    public static final DiscardingOutputStream INSTANCE = new DiscardingOutputStream();

    @Override
    public void write(int b) throws IOException {
    }

    @Override
    public void write(@NotNull byte[] b) throws IOException {
    }

    @Override
    public void write(@NotNull byte[] b, int off, int len) throws IOException {
    }

    @Override
    public void flush() throws IOException {
    }

    @Override
    public void close() throws IOException {
    }
}
