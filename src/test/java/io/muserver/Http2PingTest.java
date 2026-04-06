package io.muserver;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2PingTest {

    @Test
    void shouldReadValidPingFrame() throws Http2Exception {
        byte[] opaqueData = new byte[] {7, 6, 5, 4, 3, 2, 1, 0};
        ByteBuffer buffer = ByteBuffer.wrap(opaqueData);
        var header = new Http2FrameHeader(8, Http2FrameType.PING, 0b00000001, 0);

        var ping = Http2Ping.readFrom(header, buffer);

        assertThat(ping.isAck(), is(true));
        assertArrayEquals(opaqueData, ping.opaqueData());
    }

    @Test
    void frameSizeErrorThrownIfPayloadIsNotEightBytes() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[7]);
        var header = new Http2FrameHeader(7, Http2FrameType.PING, 0, 0);

        var ex = assertThrows(Http2Exception.class, () -> Http2Ping.readFrom(header, buffer));

        assertThat(ex.errorType(), is(Http2Level.CONNECTION));
        assertThat(ex.errorCode(), is(Http2ErrorCode.FRAME_SIZE_ERROR));
    }

    @Test
    void protocolErrorThrownIfPingIsNotOnStreamZero() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[8]);
        var header = new Http2FrameHeader(8, Http2FrameType.PING, 0, 1);

        var ex = assertThrows(Http2Exception.class, () -> Http2Ping.readFrom(header, buffer));

        assertThat(ex.errorType(), is(Http2Level.CONNECTION));
        assertThat(ex.errorCode(), is(Http2ErrorCode.PROTOCOL_ERROR));
    }

    @Test
    void shouldComparePingsCorrectly() {
        var ping1 = new Http2Ping(true, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        var ping2 = new Http2Ping(true, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

        assertThat(ping1, is(equalTo(ping2)));
        assertThat(ping1.hashCode(), is(ping2.hashCode()));
    }
}
