package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ResponseOutputCloseTest {
    @org.junit.jupiter.api.Test
    void bufferingPreservesOrderAcrossPartialFullAndDirectWrites() throws Exception {
        var writes = new ArrayList<String>();
        var wire = new ByteArrayOutputStream() {
            @Override public synchronized void write(byte[] bytes, int offset, int length) {
                writes.add(new String(bytes, offset, length, StandardCharsets.US_ASCII));
                super.write(bytes, offset, length);
            }
        };
        var out = new CloseGuardedOutputStream(new BufferedOutputStream(wire, 4));
        out.write('a');
        out.write("bc".getBytes(StandardCharsets.US_ASCII));
        assertTrue(writes.isEmpty());
        out.write("def".getBytes(StandardCharsets.US_ASCII));
        assertEquals(List.of("abc"), writes);
        out.write("_ghij_".getBytes(StandardCharsets.US_ASCII), 1, 4);
        assertEquals(List.of("abc", "def", "ghij"), writes);
        for (char value : "klmn".toCharArray()) out.write(value);
        assertEquals(3, writes.size());
        out.write('o');
        out.flush();
        out.write('p');
        out.close();
        assertEquals(List.of("abc", "def", "ghij", "klmn", "o", "p"), writes);
        assertEquals("abcdefghijklmnop", wire.toString(StandardCharsets.US_ASCII));
    }

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
            case "buffered": out = new CloseGuardedOutputStream(new BufferedOutputStream(wire, 32)); break;
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
        OutputStream out = buffered ? new CloseGuardedOutputStream(new BufferedOutputStream(delegate, 32))
            : new CloseGuardedOutputStream(delegate);
        assertThrows(IOException.class, out::close);
        assertThrows(IOException.class, () -> out.write('x'));
        out.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void delegateCloseDoesNotHoldTheOuterMonitor(boolean buffered) throws Exception {
        var outer = new AtomicReference<OutputStream>();
        var delegate = new OutputStream() {
            @Override public void write(int value) {
                assertFalse(Thread.holdsLock(outer.get()));
            }
            @Override public void write(byte[] bytes, int offset, int length) {
                assertFalse(Thread.holdsLock(outer.get()));
            }
            @Override public void flush() {
                assertFalse(Thread.holdsLock(outer.get()));
            }
            @Override public void close() {
                assertFalse(Thread.holdsLock(outer.get()), "A blocking H2 close must not gain a monitor that pins virtual threads");
            }
        };
        var out = buffered ? new CloseGuardedOutputStream(new BufferedOutputStream(delegate, 32))
            : new CloseGuardedOutputStream(delegate);
        outer.set(out);
        out.write('x');
        out.flush();
        out.write(new byte[32]);
        out.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bufferedClosePreservesJdkFailureSemanticsAndStillCloses(boolean sameFailure) throws Exception {
        var flushFailure = new IOException("flush failed");
        var closeFailure = sameFailure ? flushFailure : new IOException("close failed");
        var closes = new AtomicInteger();
        var delegate = new OutputStream() {
            @Override public void write(int value) {}
            @Override public void flush() throws IOException { throw flushFailure; }
            @Override public void close() throws IOException {
                closes.incrementAndGet();
                throw closeFailure;
            }
        };
        var out = new CloseGuardedOutputStream(new BufferedOutputStream(delegate, 32));
        assertSame(closeFailure, assertThrows(IOException.class, out::close));
        assertEquals(sameFailure ? 0 : 1, closeFailure.getSuppressed().length);
        if (!sameFailure) assertSame(flushFailure, closeFailure.getSuppressed()[0]);
        assertThrows(IOException.class, () -> out.write('x'));
        assertThrows(IOException.class, out::flush);
        out.close();
        assertEquals(1, closes.get());
    }
}
