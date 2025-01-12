package io.muserver;


import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import scaffolding.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import static io.muserver.FieldBlockEncoderTest.toByteList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;

class HuffmanEncoderTest {

    @Test
    void itCanEncodeStrings() throws IOException {
        // h    100111
        // #    111111111010
        // l    101000
        // l    101000
        // o    00111
        // ==> |10011111|11111110|10101000|10100000|111_____ (5 bits padded with EOS 1s)
        assertThat(encode("h#llo"), contains(
            (byte) 0b10011111, (byte) 0b11111110, (byte) 0b10101000, (byte) 0b10100000, (byte) 0b11111111));

        // & is an 8 bit pattern 11111000
        assertThat(encode("&&"), contains((byte) 0b11111000, (byte) 0b11111000));
    }

    @Test
    void speedTest() throws IOException {
        var out = DiscardingOutputStream.INSTANCE;
        for (int i = 0; i < 1000; i++) {
            var s = "1".repeat(i);
            out.write(s.getBytes(StandardCharsets.US_ASCII));
            HuffmanEncoder.encodeTo(out, s);
        }

        var start = System.currentTimeMillis();
        var s = StringUtils.randomAsciiStringOfLength(2000);
        for (int i = 0; i < 100000; i++) {
            out.write(s.getBytes(StandardCharsets.US_ASCII));
        }
        var duration = System.currentTimeMillis() - start;
        System.out.println("s.getBytes = " + duration);

        var hs = HeaderString.valueOf(s, HeaderString.Type.VALUE);
        start = System.currentTimeMillis();
        for (int i = 0; i < 100000; i++) {
            HuffmanEncoder.encodeTo(out, hs);
        }
        duration = System.currentTimeMillis() - start;
        System.out.println("huffman = " + duration);
    }

    @NonNull
    private static ArrayList<Byte> encode(String value) throws IOException {
        var out = new ByteArrayOutputStream();
        HuffmanEncoder.encodeTo(out, value);
        return toByteList(out);
    }


}