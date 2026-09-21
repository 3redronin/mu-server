package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/** RFC 9113 section 6.5.1: each SETTINGS value is an unsigned 32-bit network-order integer. */
class Http2SettingsSerializationTest {
    @ParameterizedTest
    @ValueSource(ints = {16384, 65535, 65536, 0x123456, 0xffffff})
    void everySettingUsesNetworkByteOrderAcrossOctetBoundaries(int value) throws Exception {
        Http2Settings settings = new Http2Settings(false, value, value, value, value, value);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        settings.writeTo(null, output);
        ByteBuffer wire = ByteBuffer.wrap(output.toByteArray());
        assertEquals(39, wire.remaining());
        byte[] header = new byte[9];
        wire.get(header);
        assertArrayEquals(new byte[] {0, 0, 30, 4, 0, 0, 0, 0, 0}, header);
        for (int id : new int[] {1, 3, 4, 5, 6}) {
            assertEquals(id, wire.getShort() & 0xffff);
            // ByteBuffer provides an independent big-endian oracle for the wire representation.
            assertEquals(value, wire.getInt(), "SETTING " + id);
        }
        assertFalse(wire.hasRemaining());
        wire.position(9);
        Http2Settings decoded = Http2Settings.readFrom(new Http2FrameHeader(30, Http2FrameType.SETTINGS, 0, 0), wire);
        assertEquals(settings, decoded);
    }

    @ParameterizedTest
    @ValueSource(ints = {0x01020304, Integer.MAX_VALUE})
    void largeNonFrameSizeSettingsPreserveAllFourOctets(int value) throws Exception {
        Http2Settings settings = new Http2Settings(false, value, value, value, 16384, value);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        settings.writeTo(null, output);
        ByteBuffer wire = ByteBuffer.wrap(output.toByteArray());
        wire.position(9);
        for (int id : new int[] {1, 3, 4, 5, 6}) {
            assertEquals(id, wire.getShort() & 0xffff);
            assertEquals(id == 5 ? 16384 : value, wire.getInt(), "SETTING " + id);
        }
        wire.position(9);
        assertEquals(settings, Http2Settings.readFrom(new Http2FrameHeader(30, Http2FrameType.SETTINGS, 0, 0), wire));
    }
}
