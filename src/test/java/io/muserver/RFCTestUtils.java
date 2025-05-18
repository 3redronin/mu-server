package io.muserver;

import org.jspecify.annotations.NonNull;
import org.junit.jupiter.params.ParameterizedTest;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RFCTestUtils {

    static Http2GoAway goAway(int lastStreamId, Http2ErrorCode code) {
        return new Http2GoAway(lastStreamId, code.code(), new byte[0]);
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
}
