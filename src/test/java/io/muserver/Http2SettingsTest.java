package io.muserver;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class Http2SettingsTest {

    @Test
    public void canWriteAckSettingsFrame() throws Exception {
        Http2Settings settings = Http2Settings.ACK;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        settings.writeTo(out);

        // Expecting a type 4 settings frame with ACK and no body
        byte[] expectedBytes = new byte[]{0, 0, 0, 4, 1, 0, 0, 0, 0};
        assertThat(out.toByteArray(), equalTo(expectedBytes));
    }

    @Test
    public void canWriteSettingsFrame() throws Exception {
        Http2Settings settings = new Http2Settings(false, 4096, 100, 65535, 16384, 32 * 1024);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        settings.writeTo(out);

        byte[] expectedBytes = new byte[]{
            0, 0, 5 * 6, 4, 0, 0, 0, 0, 0,
            0, 1, 0, 0, 16, 0,   // headerTableSize
            0, 3, 0, 0, 0, 100,  // maxConcurrentStreams
            0, 4, 0, 0, -1, -1,   // initialWindowSize
            0, 5, 0, 0, 64, 0,   // maxFrameSize
            0, 6, 0, 0, (byte)128, 0   // maxHeaderListSize
        };
        assertThat(out.toByteArray(), equalTo(expectedBytes));
    }

    @Test
    public void canReadAckSettingsFrame() throws Exception {
        ByteBuffer buffer = ByteBuffer.wrap(new byte[]{});
        Http2FrameHeader header = new Http2FrameHeader(0, Http2FrameType.SETTINGS, 0b00000001, 0);
        Http2Settings settings = Http2Settings.readFrom(header, buffer);
        assertThat(settings, equalTo(Http2Settings.ACK));
    }

    @Test
    public void canReadSettingsFrame() throws Exception {
        byte[] bytes = new byte[]{
            0, 1, 0, 0, 16, 0,    // headerTableSize
            0, 3, 0, 0, 0, 100,   // maxConcurrentStreams
            0, 4, 0, 0, -1, -1,    // initialWindowSize
            0, 5, 0, 0, 64, 0,    // maxFrameSize
            0, 6, 0, 0, (byte)128, 0    // maxHeaderListSize
        };

        long thing = 65535;

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        Http2FrameHeader header = new Http2FrameHeader(bytes.length, Http2FrameType.SETTINGS, 0, 0);
        Http2Settings settings = Http2Settings.readFrom(header, buffer);

        Http2Settings expectedSettings = new Http2Settings(false, 4096, 100, 65535, 16384, 32 * 1024);
        assertThat(settings, equalTo(expectedSettings));
    }

    @Test
    public void frameSizeErrorThrownForInvalidFrameSize() {
        byte[] invalidSettingsFrame = new byte[]{0, 1, 0, 0, 16, 0, 0, 3};
        ByteBuffer buffer = ByteBuffer.wrap(invalidSettingsFrame);
        Http2FrameHeader invalidHeader = new Http2FrameHeader(7, Http2FrameType.SETTINGS, 0, 0); // Invalid length
        assertThrows(Http2Exception.class, () -> Http2Settings.readFrom(invalidHeader, buffer));
    }

    @Test
    public void settingsAckWithNonZeroPayloadThrowsFrameSizeError() {
        byte[] nonEmptyPayload = new byte[]{0, 1, 0, 0, 16, 0}; // Length is 6, but ACK should have no body
        ByteBuffer buffer = ByteBuffer.wrap(nonEmptyPayload);
        Http2FrameHeader header = new Http2FrameHeader(6, Http2FrameType.SETTINGS, 0b00000001, 0); // ACK with payload
        assertThrows(Http2Exception.class, () -> Http2Settings.readFrom(header, buffer));
    }

    @Test
    public void settingsEqualityCheckWorks() {
        Http2Settings settings1 = new Http2Settings(false, 4096, 100, 65535, 16384, 32 * 1024);
        Http2Settings settings2 = new Http2Settings(false, 4096, 100, 65535, 16384, 32 * 1024);

        assertThat(settings1.equals(settings2), equalTo(true));
        assertThat(settings1.hashCode(), equalTo(settings2.hashCode()));
    }
}
