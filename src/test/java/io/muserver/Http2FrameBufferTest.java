package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;

import static org.junit.jupiter.api.Assertions.*;

class Http2FrameBufferTest {
    @ParameterizedTest @ValueSource(ints = {0, 1, 255, 256, 257, 8192, 16384, 16385, 16392, 16393, 16394, 65536})
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

    @Test
    void fullSizeDataFramesReuseTheBoundedEncodingCache() {
        var encoding = new Http2FrameBuffer();
        byte[] header = new byte[Http2FrameHeader.FRAME_HEADER_LENGTH];
        byte[] payload = new byte[16384];
        new Random(9917).nextBytes(payload);
        var buffers = Collections.newSetFromMap(new IdentityHashMap<byte[], Boolean>());
        for (int i = 0; i < 128; i++) {
            header[8] = (byte) i;
            encoding.write(header, 0, header.length);
            encoding.write(payload, 0, payload.length);
            buffers.add(encoding.rawBuffer());
            assertEquals(header.length + payload.length, encoding.size());
            assertEquals((byte) i, encoding.rawBuffer()[8]);
            assertArrayEquals(payload, Arrays.copyOfRange(encoding.rawBuffer(), header.length, encoding.size()));
            encoding.release();
        }
        assertTrue(buffers.size() <= 64,
            "A full DATA frame plus its header must reuse the bounded cache, rather than allocate on every write");
    }
}
