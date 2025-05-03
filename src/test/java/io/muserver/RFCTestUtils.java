package io.muserver;

import org.jspecify.annotations.NonNull;

class RFCTestUtils {

    static Http2GoAway goAway(int lastStreamId, Http2ErrorCode code) {
        return new Http2GoAway(lastStreamId, code.code(), new byte[0]);
    }


    static @NonNull FieldBlock getHelloHeaders(int port) {
        FieldBlock headers = FieldBlock.newWithDate();
        headers.add(":method", "GET");
        headers.add(":scheme", "https");
        headers.add(":path", "/hello");
        headers.add(":authority", "localhost:" + port);
        return headers;
    }
}
