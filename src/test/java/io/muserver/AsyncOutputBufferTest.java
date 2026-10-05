package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class AsyncOutputBufferTest {
    @ParameterizedTest
    @CsvSource({"array,0", "array,37", "array,20003", "direct,0", "direct,37", "direct,20003",
        "readonly,0", "readonly,37", "readonly,20003"})
    void copiesOnlyTheSelectedRangeWithBoundedScratchSpace(String kind, int length) throws Exception {
        byte[] bytes = new byte[length + 23];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31);
        ByteBuffer data;
        if (kind.equals("direct")) {
            data = ByteBuffer.allocateDirect(bytes.length);
            data.put(bytes).flip();
        } else {
            data = ByteBuffer.wrap(bytes);
            if (kind.equals("readonly")) data = data.asReadOnlyBuffer();
        }
        data.position(7);
        data = data.slice();
        data.position(3).limit(3 + length);
        var destination = new ByteArrayOutputStream() {
            int flushes;
            @Override public void write(byte[] buffer, int offset, int count) {
                if (!kind.equals("array")) assertTrue(buffer.length <= 8192, "Scratch allocation follows payload size");
                super.write(buffer, offset, count);
            }
            @Override public void flush() { flushes++; }
        };
        Mu3AsyncHandleImpl.copyBufferToOutput(data, destination);
        assertArrayEquals(Arrays.copyOfRange(bytes, 10, 10 + length), destination.toByteArray());
        assertEquals(1, destination.flushes);
        assertEquals(kind.equals("array") ? 3 : data.limit(), data.position());
    }
}
