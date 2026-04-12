package io.muserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

import static io.muserver.FieldBlockDecoder.readHpackInt;
import static io.muserver.FieldBlockEncoder.writeHpackInt;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("RFC 7541 5.1 Integer Representation")
class RFC7541_5_1_IntegerRepresentationTest {

    @Test
    void appendixCExamplesCanBeEncoded() throws Exception {
        assertThat(writeBytes(5, (byte) 0, 10), contains((byte) 10));
        assertThat(writeBytes(5, (byte) 0, 1337), contains((byte) 0x1f, (byte) 0x9a, (byte) 0x0a));
        assertThat(writeBytes(8, (byte) 0, 42), contains((byte) 42));
    }

    @Test
    void appendixCExamplesCanBeDecoded() throws Exception {
        assertThat(readHpackInt(5, (byte) 10, ByteBuffer.allocate(0)), equalTo(10));
        assertThat(readHpackInt(5, (byte) 0x1f, ByteBuffer.wrap(new byte[] {(byte) 0x9a, (byte) 0x0a})), equalTo(1337));
        assertThat(readHpackInt(8, (byte) 42, ByteBuffer.allocate(0)), equalTo(42));
    }

    @Test
    void integersThatGoOnForTooLongAreCompressionErrors() {
        var ex = assertThrows(Http2Exception.class, () -> readHpackInt(8, (byte) 0xff, ByteBuffer.wrap(new byte[] {
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x80,
            (byte) 0x00
        })));

        assertThat(ex.errorCode(), equalTo(Http2ErrorCode.COMPRESSION_ERROR));
        assertThat(ex.getMessage(), equalTo("hpack integer too long"));
    }

    @Test
    void integersThatOverflowAreCompressionErrors() {
        var ex = assertThrows(Http2Exception.class, () -> readHpackInt(8, (byte) 0xff,
            ByteBuffer.wrap(new byte[] {(byte) 0xe0, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x08})
        ));

        assertThat(ex.errorCode(), equalTo(Http2ErrorCode.COMPRESSION_ERROR));
        assertThat(ex.getMessage(), equalTo("hpack integer overflow"));
    }

    private static java.util.List<Byte> writeBytes(int n, byte prefix, int value) throws Exception {
        try (var out = new ByteArrayOutputStream()) {
            writeHpackInt(n, prefix, out, value);
            return FieldBlockEncoderTest.toByteList(out);
        }
    }
}

