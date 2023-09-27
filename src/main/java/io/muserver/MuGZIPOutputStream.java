package io.muserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

class MuGZIPOutputStream extends GZIPOutputStream {
    private final ByteArrayOutputStream baos;

    MuGZIPOutputStream(ByteArrayOutputStream baos) throws IOException {
        super(baos, true);
        this.baos = baos;
    }

    byte[] getAndClear() {
        byte[] bytes = baos.toByteArray();
        baos.reset();
        return bytes;
    }

    int written() {
        return baos.size();
    }
}
