package io.muserver;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

@DisplayName("RFC 9113 6.1 Frame Definitions: DATA")
class RFC9113_6_1_DataFrameTest {

    private @Nullable MuServer server;

    @Test
    void dataFrameSerialisationTest() throws Exception {
        roundTrip(new Http2DataFrame(1, false, new byte[0], 0, 0));
        roundTrip(new Http2DataFrame(2, true, "Hello".getBytes(StandardCharsets.UTF_8), 0, 5));
    }

    private void roundTrip(Http2DataFrame example) throws IOException, Http2Exception {
        var baos = new ByteArrayOutputStream();
        example.writeTo(null, baos);
        var buffer = ByteBuffer.wrap(baos.toByteArray());
        var header = Http2FrameHeader.readFrom(buffer);
        var recreated = Http2DataFrame.readFrom(header, buffer);
        assertThat(recreated, equalTo(example));
    }

    @Test
    void dataFramesCanHavePaddingWhichIsIgnored() throws Exception {
        var baos = new ByteArrayOutputStream();

        var data = "This is a payload".getBytes(StandardCharsets.UTF_8);
        var paddingLength = 255;

        var payloadLength = 1 /* padding length declaration */ + data.length + paddingLength;
        var streamId = 3;

        baos.write(new byte[] {
            // len
            (byte)(payloadLength >> 16),
            (byte)(payloadLength >> 8),
            (byte)payloadLength,
            // type data
            (byte)0,
            // flags - padding bit and EOS and unused set
            0b00101001,
            // stream id
            (byte)(streamId >> 24),
            (byte)(streamId >> 16),
            (byte)(streamId >> 8),
            (byte)(streamId),
        });

        baos.write(paddingLength);
        baos.write(data);
        baos.write(new byte[paddingLength]);

        var buffer = ByteBuffer.wrap(baos.toByteArray());
        var header = Http2FrameHeader.readFrom(buffer);
        var recreated = Http2DataFrame.readFrom(header, buffer);
        assertThat(recreated, equalTo(new Http2DataFrame(streamId, true, data, 0, data.length)));

    }








    @AfterEach
    public void stop() {
        if (server != null) server.stop();
    }

}
