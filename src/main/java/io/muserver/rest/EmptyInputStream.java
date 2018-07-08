package io.muserver.rest;

import java.io.InputStream;

class EmptyInputStream extends InputStream {
    static final InputStream INSTANCE = new EmptyInputStream();

    private EmptyInputStream() {
    }

    public int read() {
        return -1;
    }
}
