package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/** RFC 9113 sections 4.1 and 6: frame header rules independent of connection state. */
class Http2FrameHeaderConformanceTest {
    private static ByteBuffer header(int length, int type, int flags, int stream) {
        ByteBuffer bytes = ByteBuffer.allocate(Math.max(9, length));
        bytes.put((byte) (length >>> 16)).put((byte) (length >>> 8)).put((byte) length);
        bytes.put((byte) type).put((byte) flags).putInt(stream).flip();
        return bytes;
    }

    @ParameterizedTest
    @CsvSource({"0,0", "1,0", "2,5", "3,4", "9,0"})
    void streamFramesRequireNonzeroStream(int type, int length) {
        Http2Exception error = assertThrows(Http2Exception.class,
            () -> Http2FrameHeader.readFrom(header(length, type, 0, 0)));
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, error.errorCode());
        assertEquals(Http2Level.CONNECTION, error.errorType());
    }

    @ParameterizedTest
    @CsvSource({"4,0", "6,8", "7,8"})
    void connectionFramesRequireStreamZero(int type, int length) {
        Http2Exception error = assertThrows(Http2Exception.class,
            () -> Http2FrameHeader.readFrom(header(length, type, 0, 1)));
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, error.errorCode());
        assertEquals(Http2Level.CONNECTION, error.errorType());
    }

    @ParameterizedTest
    @CsvSource({"3,3,1", "3,5,1", "6,7,0", "6,9,0", "8,3,0", "8,5,1"})
    void fixedLengthFramesRejectWrongLength(int type, int length, int stream) {
        Http2Exception error = assertThrows(Http2Exception.class,
            () -> Http2FrameHeader.readFrom(header(length, type, 0, stream)));
        assertEquals(Http2ErrorCode.FRAME_SIZE_ERROR, error.errorCode());
        assertEquals(Http2Level.CONNECTION, error.errorType());
    }

    @ParameterizedTest
    @CsvSource({"3,4,1", "6,8,0", "8,4,0", "8,4,1"})
    void fixedLengthFramesAcceptExactLength(int type, int length, int stream) throws Exception {
        ByteBuffer bytes = header(length, type, 0, stream);
        Http2FrameHeader decoded = Http2FrameHeader.readFrom(bytes);
        assertEquals(length, decoded.length());
        assertEquals(stream, decoded.streamId());
        assertEquals(9, bytes.position());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2147483647})
    void reservedBitIsIgnored(int stream) throws Exception {
        Http2FrameHeader decoded = Http2FrameHeader.readFrom(header(4, 8, 0, stream | Integer.MIN_VALUE));
        assertEquals(stream, decoded.streamId());
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 127, 128, 255})
    void unknownFrameTypesRemainAvailableForConnectionLayerToIgnore(int type) throws Exception {
        Http2FrameHeader decoded = Http2FrameHeader.readFrom(header(0, type, 255, 1));
        assertEquals(Http2FrameType.UNKNOWN, decoded.frameType());
        assertEquals(255, decoded.flags());
        assertEquals(1, decoded.streamId());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 255, 256, 65535, 65536})
    void lengthIsAnUnsigned24BitValue(int length) throws Exception {
        assertEquals(length, Http2FrameHeader.readFrom(header(length, 0, 0, 1)).length());
    }
}
