package io.muserver;


import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

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

}