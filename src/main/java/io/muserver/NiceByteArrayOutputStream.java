package io.muserver;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;

/**
 * A BAOS that lets you get the raw buffer without making a copy of it
 */
class NiceByteArrayOutputStream extends ByteArrayOutputStream {
    NiceByteArrayOutputStream(int size) {
        super(size);
    }

    ByteBuffer toByteBuffer() {
        return ByteBuffer.wrap(buf, 0, count);
    }

    byte[] rawBuffer() {
        return buf;
    }

    String decodeUTF8() throws CharacterCodingException {
        var charBuffer = StandardCharsets.UTF_8.newDecoder().decode(toByteBuffer());
        return charBuffer.toString();
    }

}
