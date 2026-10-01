package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ResponseOutputCloseTest {
    @ParameterizedTest
    @ValueSource(strings = {"fixed", "chunk", "delimited", "discard", "buffered", "encoder"})
    void closedOutputCannotWriteOrFlushUnderlyingTransport(String kind) throws Exception {
        var wire = new ByteArrayOutputStream();
        OutputStream out;
        switch (kind) {
            case "fixed": out = new FixedSizeOutputStream(1, wire); break;
            case "chunk": out = new ChunkedOutputStream(wire); break;
            case "delimited": out = new CloseDelimitedOutputStream(wire); break;
            case "discard": out = new DiscardingOutputStream(); break;
            case "buffered": out = new CloseGuardedBufferedOutputStream(wire, 32); break;
            default: out = new CloseGuardedOutputStream(wire);
        }
        out.write('x');
        out.close();
        byte[] original = wire.toByteArray();
        assertThrows(IOException.class, () -> out.write('y'));
        assertThrows(IOException.class, () -> out.write(new byte[] {1}));
        assertThrows(IOException.class, () -> out.write(new byte[0], 0, 0));
        assertThrows(IOException.class, out::flush);
        out.close();
        assertArrayEquals(original, wire.toByteArray());
        var other = new DiscardingOutputStream();
        other.write('x'); // Closing one bodyless response must not close another.
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCloseStillClosesOuterStream(boolean buffered) throws Exception {
        var delegate = new OutputStream() {
            @Override public void write(int value) {}
            @Override public void close() throws IOException { throw new IOException("close failed"); }
        };
        OutputStream out = buffered ? new CloseGuardedBufferedOutputStream(delegate, 32)
            : new CloseGuardedOutputStream(delegate);
        assertThrows(IOException.class, out::close);
        assertThrows(IOException.class, () -> out.write('x'));
        out.close();
    }
}
