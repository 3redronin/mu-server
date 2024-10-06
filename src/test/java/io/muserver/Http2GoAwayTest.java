package io.muserver;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

class Http2GoAwayTest {

    @Test
    void shouldReadValidGoAwayFrameWithDebugData() throws Http2Exception {
        byte[] debugData = "Debug info".getBytes(StandardCharsets.UTF_8);
        int lastStreamId = 5;
        int errorCode = Http2ErrorCode.COMPRESSION_ERROR.code(); // Assume some valid error code

        ByteBuffer buffer = ByteBuffer.allocate(8 + debugData.length);
        buffer.putInt(lastStreamId);
        buffer.putInt(errorCode);
        buffer.put(debugData);
        buffer.flip();

        var header = new Http2FrameHeader(8 + debugData.length, Http2FrameType.GOAWAY, 0, 0); // streamId 0 for GOAWAY

        Http2GoAway goAway = Http2GoAway.readFrom(header, buffer);

        assertThat(goAway.lastStreamId(), is(lastStreamId));
        assertThat(goAway.errorCode(), is(errorCode));
        assertThat(goAway.errorCodeEnum(), is(Http2ErrorCode.COMPRESSION_ERROR));
        assertThat(goAway.debugDataAsUTF8String(), is("Debug info"));
        assertThat(goAway.debugData(), is(debugData));
    }

    @Test
    void shouldReadGoAwayFrameWithoutDebugData() throws Http2Exception {
        int lastStreamId = 3;
        int errorCode = 1;

        ByteBuffer buffer = ByteBuffer.allocate(8); // No debug data, just lastStreamId and errorCode
        buffer.putInt(lastStreamId);
        buffer.putInt(errorCode);
        buffer.flip();

        var header = new Http2FrameHeader(8, Http2FrameType.GOAWAY, 0, 0); // streamId 0 for GOAWAY

        Http2GoAway goAway = Http2GoAway.readFrom(header, buffer);

        assertThat(goAway.lastStreamId(), is(lastStreamId));
        assertThat(goAway.errorCode(), is(errorCode));
        assertThat(goAway.debugData().length, is(0));
    }


    @Test
    void shouldCompareGoAwayFramesCorrectly() {
        byte[] debugData = "Test".getBytes(StandardCharsets.UTF_8);
        Http2GoAway goAway1 = new Http2GoAway(3, 1, debugData);
        Http2GoAway goAway2 = new Http2GoAway(3, 1, debugData);

        assertThat(goAway1, is(equalTo(goAway2)));
        assertThat(goAway1.hashCode(), is(goAway2.hashCode()));
    }

    @Test
    void shouldHandleUTF8DebugData() {
        byte[] debugData = "Multilingual 文字".getBytes(StandardCharsets.UTF_8);
        Http2GoAway goAway = new Http2GoAway(3, 1, debugData);

        assertThat(goAway.debugDataAsUTF8String(), is("Multilingual 文字"));
    }
}
