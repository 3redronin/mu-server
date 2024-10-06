package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2WindowUpdateTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 0x7FFFFFFF})
    void shouldReadValidWindowUpdateFrame(int streamId) throws Http2Exception {
        var header = new Http2FrameHeader(4, Http2FrameType.WINDOW_UPDATE, 0, streamId);
        var buffer = ByteBuffer.allocate(4);
        buffer.putInt(12345).flip(); // prepare buffer for reading

        Http2WindowUpdate windowUpdate = Http2WindowUpdate.readFrom(header, buffer);

        assertThat(windowUpdate.streamId(), is(streamId));
        assertThat(windowUpdate.windowSizeIncrement(), is(12345));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 0x7FFFFFFF})
    void shouldThrowProtocolErrorForZeroWindowSizeIncrement(int streamId) {
        var header = new Http2FrameHeader(4, Http2FrameType.WINDOW_UPDATE, 0, streamId);
        var buffer = ByteBuffer.allocate(4);
        buffer.putInt(0).flip();

        Http2Exception exception = assertThrows(Http2Exception.class, () -> Http2WindowUpdate.readFrom(header, buffer));

        assertThat(exception.getMessage(), containsString("window size increment 0"));
        assertThat(exception.errorCode(), is(Http2ErrorCode.PROTOCOL_ERROR));
        if (streamId == 0) {
            assertThat(exception.errorType(), is(Http2Level.CONNECTION));
        } else {
            assertThat(exception.errorType(), is(Http2Level.STREAM));
        }
        assertThat(exception.streamId(), is(streamId));
    }


    @ParameterizedTest
    @ValueSource(ints = {0, 1, 0x7FFFFFFF})
    void shouldHandleMaximumWindowSizeIncrement(int streamId) throws Http2Exception {
        var header = new Http2FrameHeader(4, Http2FrameType.WINDOW_UPDATE, 0, streamId);
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(0x7FFFFFFF); // Maximum positive value for 31-bit unsigned int
        buffer.flip(); // prepare buffer for reading

        Http2WindowUpdate windowUpdate = Http2WindowUpdate.readFrom(header, buffer);

        assertThat(windowUpdate.streamId(), is(streamId));
        assertThat(windowUpdate.windowSizeIncrement(), is(0x7FFFFFFF));
    }

    @Test
    void shouldCompareWindowUpdatesCorrectly() throws Http2Exception {
        Http2WindowUpdate update1 = new Http2WindowUpdate(1, 12345);
        Http2WindowUpdate update2 = new Http2WindowUpdate(1, 12345);

        assertThat(update1, is(equalTo(update2)));
        assertThat(update1.hashCode(), is(update2.hashCode()));
    }

}
