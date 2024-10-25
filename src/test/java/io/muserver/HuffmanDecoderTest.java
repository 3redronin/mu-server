package io.muserver;


import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static io.muserver.FieldBlockEncoderTest.hexToByteArray;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

class HuffmanDecoderTest {

    @Test
    void itCanDecode() {
        assertThat(HuffmanDecoder.decodeFrom(ByteBuffer.wrap(new byte[] {
            (byte) 0b10011111, (byte) 0b11111110, (byte) 0b10101000, (byte) 0b10100000, (byte) 0b11111111
        }), 5, HeaderString.Type.VALUE).toString(), equalTo("h#llo"));

        assertThat(HuffmanDecoder.decodeFrom(ByteBuffer.wrap(new byte[] {
            (byte) 0b11111000, (byte) 0b11111000
        }), 2, HeaderString.Type.VALUE).toString(), equalTo("&&"));
    }

    @Test
    void exampleDotCom() {
        assertThat(HuffmanDecoder.decodeFrom(ByteBuffer.wrap(
            hexToByteArray("f1e3 c2e5 f23a 6ba0 ab90 f4ff")
        ), 12, HeaderString.Type.VALUE).toString(), equalTo("www.example.com"));
    }

}