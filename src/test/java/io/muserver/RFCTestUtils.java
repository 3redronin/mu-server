package io.muserver;

import org.jspecify.annotations.NonNull;
import java.io.ByteArrayOutputStream;
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

    static byte[] encodeFieldBlock(FieldBlock headers) throws IOException {
        var out = new ByteArrayOutputStream();
        new FieldBlockEncoder(new HpackTable(Http2Settings.DEFAULT_CLIENT_SETTINGS.headerTableSize)).encodeTo(headers, out);
        return out.toByteArray();
    }

    static byte[] headersFrame(int streamId, boolean endStream, boolean endHeaders, byte[] fragment) {
        return headersFrame(streamId, endStream, endHeaders, fragment, 0, false, 0, 0, false);
    }

    static byte[] paddedHeadersFrame(int streamId, boolean endStream, boolean endHeaders, byte[] fragment, int padLength) {
        return headersFrame(streamId, endStream, endHeaders, fragment, padLength, false, 0, 0, false);
    }

    static byte[] priorityHeadersFrame(int streamId, boolean endStream, boolean endHeaders, byte[] fragment, boolean exclusive, int dependencyStreamId, int weight) {
        return headersFrame(streamId, endStream, endHeaders, fragment, 0, true, dependencyStreamId, weight, exclusive);
    }

    static byte[] continuationFrame(int streamId, boolean endHeaders, byte[] fragment) {
        int payloadLength = fragment.length;
        byte[] frame = new byte[9 + payloadLength];
        frame[0] = (byte) (payloadLength >> 16);
        frame[1] = (byte) (payloadLength >> 8);
        frame[2] = (byte) payloadLength;
        frame[3] = 0x09;
        frame[4] = (byte) (endHeaders ? 0b00000100 : 0);
        frame[5] = (byte) (streamId >> 24);
        frame[6] = (byte) (streamId >> 16);
        frame[7] = (byte) (streamId >> 8);
        frame[8] = (byte) streamId;
        System.arraycopy(fragment, 0, frame, 9, fragment.length);
        return frame;
    }

    private static byte[] headersFrame(int streamId, boolean endStream, boolean endHeaders, byte[] fragment, int padLength, boolean priority, int dependencyStreamId, int weight, boolean exclusive) {
        int priorityLength = priority ? 5 : 0;
        int paddingFieldLength = padLength > 0 ? 1 : 0;
        int payloadLength = paddingFieldLength + priorityLength + fragment.length + padLength;
        byte[] frame = new byte[9 + payloadLength];
        frame[0] = (byte) (payloadLength >> 16);
        frame[1] = (byte) (payloadLength >> 8);
        frame[2] = (byte) payloadLength;
        frame[3] = 0x01;

        int flags = 0;
        if (endStream) flags |= 0b00000001;
        if (endHeaders) flags |= 0b00000100;
        if (padLength > 0) flags |= 0b00001000;
        if (priority) flags |= 0b00100000;
        frame[4] = (byte) flags;

        frame[5] = (byte) (streamId >> 24);
        frame[6] = (byte) (streamId >> 16);
        frame[7] = (byte) (streamId >> 8);
        frame[8] = (byte) streamId;

        int offset = 9;
        if (padLength > 0) {
            frame[offset++] = (byte) padLength;
        }
        if (priority) {
            int dependency = exclusive ? (dependencyStreamId | 0x80000000) : dependencyStreamId;
            frame[offset++] = (byte) (dependency >> 24);
            frame[offset++] = (byte) (dependency >> 16);
            frame[offset++] = (byte) (dependency >> 8);
            frame[offset++] = (byte) dependency;
            frame[offset++] = (byte) weight;
        }
        System.arraycopy(fragment, 0, frame, offset, fragment.length);
        return frame;
    }
    static @NonNull Http2DataFrame emptyEosDataFrame(int streamId) {
        return new Http2DataFrame(streamId, true, new byte[0], 0, 0);
    }

    static @NonNull FieldBlock getHelloHeaders(int port) {
        FieldBlock headers = baseHeaders("https", port);
        headers.add(":method", "GET");
        headers.add(":path", "/hello");
        return headers;
    }

    static @NonNull FieldBlock getHelloHeaders(String scheme, int port) {
        FieldBlock headers = baseHeaders(scheme, port);
        headers.add(":method", "GET");
        headers.add(":path", "/hello");
        return headers;
    }

    static @NonNull FieldBlock postHelloHeaders(int port) {
        FieldBlock headers = baseHeaders("https", port);
        headers.add(":method", "POST");
        headers.add(":path", "/hello");
        headers.add("content-type", "text/plain; charset=utf-8");
        return headers;
    }

    private static @NonNull FieldBlock baseHeaders(String scheme, int port) {
        FieldBlock headers = new FieldBlock();
        headers.add(":scheme", scheme);
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
