package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class Http2DataWriteTest {
    @ParameterizedTest
    @CsvSource({"0,false", "0,true", "1,false", "5,true", "8192,false", "8192,true"})
    void consolidatedWritePreservesWireBytesAndPayload(int length, boolean end) throws Exception {
        byte[] payload = new byte[length + 6];
        Arrays.fill(payload, (byte) 67);
        var frame = new Http2DataFrame(0x1234567, end, payload, 3, length);
        var original = new ByteArrayOutputStream();
        frame.writeTo(null, original);
        var actual = new ByteArrayOutputStream();
        int[] calls = {0};
        var out = new OutputStream() {
            @Override public void write(int value) { fail("Expected bulk output"); }
            @Override public void write(byte[] bytes, int offset, int count) {
                calls[0]++;
                actual.write(bytes, offset, count);
            }
            @Override public void flush() { fail("Connection writer owns flushing"); }
        };
        byte[] buffer = new byte[8201];
        frame.writeConsolidatedTo(out, buffer);
        assertEquals(1, calls[0]);
        assertArrayEquals(original.toByteArray(), actual.toByteArray());
        for (byte value : payload) assertEquals(67, value);
        // Reusing a larger buffer must not send bytes from the previous frame.
        actual.reset();
        Http2DataFrame.eos(1).writeConsolidatedTo(out, buffer);
        assertEquals(9, actual.size());
        assertEquals(1, actual.toByteArray()[4]);
    }
}
