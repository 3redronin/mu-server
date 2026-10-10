package io.muserver;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.ParseException;
import java.util.Queue;

/** Blocking stream driver for the transport-independent HTTP/1 decoder. */
class Http1MessageParser implements Http1MessageReader {
    private final InputStream source;
    private final Http1MessageDecoder decoder;
    final byte[] readBuffer = new byte[8192];
    private final ByteBuffer input = ByteBuffer.wrap(readBuffer).flip();

    Http1MessageParser(HttpMessageType type, Queue<HttpRequestTemp> requestQueue, InputStream source,
                       int maxHeadersLength, int maxUrlLength) {
        this.source = source;
        this.decoder = new Http1MessageDecoder(type, requestQueue, maxHeadersLength, maxUrlLength);
    }

    @Override
    public Http1ConnectionMsg readNext() throws IOException, ParseException {
        try {
            for (;;) {
                Http1ConnectionMsg next = decoder.decode(input);
                if (next != null) return next;
                input.clear();
                int read = source.read(readBuffer);
                input.limit(Math.max(0, read));
                if (read == -1) return decoder.endOfInput();
            }
        } catch (IOException e) {
            decoder.fail();
            throw e;
        }
    }

    @Nullable
    FieldBlock takeTrailers() {
        return decoder.takeTrailers();
    }

    HttpRequestTemp rejectInvalidRequest(ParseException failure) {
        return decoder.rejectInvalidRequest(failure);
    }

    @Override
    public String toString() {
        return decoder.toString();
    }
}
