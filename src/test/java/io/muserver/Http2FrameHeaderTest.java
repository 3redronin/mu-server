package io.muserver;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2FrameHeaderTest {

    @Test
    void priorityFramesMustHaveAStreamID() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] {
            0, 0, 5,
            0x02,
            0,
            0, 0, 0, 0
        });

        var ex = assertThrows(Http2Exception.class, () -> Http2FrameHeader.readFrom(buffer));

        assertThat(ex.errorType(), is(Http2Level.CONNECTION));
        assertThat(ex.errorCode(), is(Http2ErrorCode.PROTOCOL_ERROR));
    }

    @Test
    void continuationFramesMustHaveAStreamID() {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[] {
            0, 0, 1,
            0x09,
            0,
            0, 0, 0, 0
        });

        var ex = assertThrows(Http2Exception.class, () -> Http2FrameHeader.readFrom(buffer));

        assertThat(ex.errorType(), is(Http2Level.CONNECTION));
        assertThat(ex.errorCode(), is(Http2ErrorCode.PROTOCOL_ERROR));
    }
}

