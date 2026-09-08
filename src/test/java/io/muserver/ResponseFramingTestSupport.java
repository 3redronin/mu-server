package io.muserver;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

final class ResponseFramingTestSupport {
    enum WriteStyle { NONE, EMPTY_STREAM, EMPTY_WRITER, WRITE, STREAM, WRITER, CHUNK, ASYNC }

    static void writeBody(WriteStyle style, MuRequest request, MuResponse response) throws Exception {
        switch (style) {
            case NONE: break;
            case EMPTY_STREAM: response.outputStream(); break;
            case EMPTY_WRITER: response.writer(); break;
            case WRITE: response.write("hello"); break;
            case STREAM:
                response.outputStream().write('h');
                response.outputStream().write("ello".getBytes(StandardCharsets.US_ASCII));
                response.outputStream().flush();
                break;
            case WRITER: response.writer().write("hello"); break;
            case CHUNK: response.sendChunk("hello"); break;
            case ASYNC:
                var async = request.handleAsync();
                async.write(ByteBuffer.wrap("hello".getBytes(StandardCharsets.US_ASCII)), async::complete);
                break;
        }
    }
}
