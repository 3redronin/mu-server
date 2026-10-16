package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class Http2FrameBufferTest {
    @ParameterizedTest @ValueSource(ints = {0, 1, 255, 256, 257, 8192, 16384, 16385, 65536})
    void encodingPreservesPrefixesAcrossGrowthAndSubsequentReuse(int size) {
        var encoding = new Http2FrameBuffer();
        byte[] payload = new byte[size + 4];
        new Random(7811).nextBytes(payload);
        for (int repeat = 0; repeat < 3; repeat++) {
            encoding.write(repeat);
            for (int offset = 0; offset < size; offset += 137) {
                encoding.write(payload, offset + 2, Math.min(137, size - offset));
            }
            byte[] expected = new byte[size + 1];
            expected[0] = (byte) repeat;
            System.arraycopy(payload, 2, expected, 1, size);
            assertArrayEquals(expected, Arrays.copyOf(encoding.rawBuffer(), encoding.size()));
            encoding.release();
            assertEquals(0, encoding.size());
            assertEquals(256, encoding.rawBuffer().length, "Idle connections must release large encoding storage");
        }
    }
}
