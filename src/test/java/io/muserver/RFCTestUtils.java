package io.muserver;

import org.jspecify.annotations.NonNull;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RFCTestUtils {

    static Http2GoAway goAway(int lastStreamId, Http2ErrorCode code) {
        return new Http2GoAway(lastStreamId, code.code(), new byte[0]);
    }

    static @NonNull Http2DataFrame utf8DataFrame(int streamId, boolean endStream, String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        return new Http2DataFrame(streamId, endStream, bytes, 0, bytes.length);
    }

    static byte[] paddedDataFrame(int streamId, boolean endStream, byte[] data, int padLength) {
        int payloadLength = 1 + data.length + padLength;
        byte[] frame = new byte[9 + payloadLength];
        frame[0] = (byte) (payloadLength >> 16);
        frame[1] = (byte) (payloadLength >> 8);
        frame[2] = (byte) payloadLength;
        frame[3] = 0x00;
        frame[4] = (byte) ((endStream ? 0b00000001 : 0) | 0b00001000);
        frame[5] = (byte) (streamId >> 24);
        frame[6] = (byte) (streamId >> 16);
        frame[7] = (byte) (streamId >> 8);
        frame[8] = (byte) streamId;
        frame[9] = (byte) padLength;
        System.arraycopy(data, 0, frame, 10, data.length);
        return frame;
    }
    static @NonNull Http2DataFrame emptyEosDataFrame(int streamId) {
        return new Http2DataFrame(streamId, true, new byte[0], 0, 0);
    }

    static @NonNull FieldBlock getHelloHeaders(int port) {
        FieldBlock headers = baseHeaders(port);
        headers.add(":method", "GET");
        headers.add(":path", "/hello");
        return headers;
    }

    static @NonNull FieldBlock postHelloHeaders(int port) {
        FieldBlock headers = baseHeaders(port);
        headers.add(":method", "POST");
        headers.add(":path", "/hello");
        headers.add("content-type", "text/plain; charset=utf-8");
        return headers;
    }

    private static @NonNull FieldBlock baseHeaders(int port) {
        FieldBlock headers = FieldBlock.newWithDate();
        headers.add(":scheme", "https");
        headers.add(":authority", "localhost:" + port);
        return headers;
    }

    static void assertNothingToRead(Socket socket) throws IOException {
        var beforeTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(20);
            assertThrows(SocketTimeoutException.class, () -> socket.getInputStream().read());
        } finally {
            socket.setSoTimeout(beforeTimeout);
        }
    }

    static <T extends LogicalHttp2Frame> T readIgnoringWindowUpdates(H2ClientConnection con, Class<T> clazz) throws IOException, Http2Exception {
        while (true) {
            var frame = con.readLogicalFrame();
            if (clazz.isAssignableFrom(frame.getClass())) {
                return clazz.cast(frame);
            }
            if (!(frame instanceof Http2WindowUpdate)) {
                throw new IllegalStateException("Expected " + clazz.getName() + ", got " + frame);
            }
        }
    }
}
