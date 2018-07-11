package io.muserver.rest;

import java.io.OutputStream;

class NullOutputStream extends OutputStream {
    static final NullOutputStream INSTANCE = new NullOutputStream();
    private NullOutputStream() {}
    @Override
    public void write(byte[] b) {
    }
    @Override
    public void write(byte[] b, int off, int len) {
    }
    @Override
    public void flush() {
    }
    @Override
    public void close() {
    }
    @Override
    public void write(int b) {
    }
}
