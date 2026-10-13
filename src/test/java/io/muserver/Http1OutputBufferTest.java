package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Http1OutputBufferTest {
    @Test void framingAndBufferSizedPayloadUseOneFullTransportWrite() throws Exception {
        var wire = new RecordingOutput();
        var buffered = new Http1OutputBuffer(wire, 8192);
        var chunked = new ChunkedOutputStream(buffered);
        byte[] body = new byte[8192];
        new Random(8123).nextBytes(body);
        chunked.write(body);
        assertEquals(List.of(8192), wire.writes);
        assertEquals(0, wire.flushes);
        chunked.close();
        var expected = new ByteArrayOutputStream();
        expected.write("2000\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        expected.write(body);
        expected.write("\r\n0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertArrayEquals(expected.toByteArray(), wire.toByteArray());
        assertEquals(List.of(8192, 13), wire.writes);
        assertEquals(1, wire.flushes);
        assertEquals(0, wire.closes);
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 8, 8192})
    void chunkFramingRemainsExactAcrossSmallAndOversizedWrites(int capacity) throws Exception {
        var wire = new RecordingOutput();
        var actual = new ChunkedOutputStream(new Http1OutputBuffer(wire, capacity));
        var expectedBytes = new ByteArrayOutputStream();
        var expected = new ChunkedOutputStream(expectedBytes);
        var random = new Random(894);
        for (int size : new int[]{0, 1, 15, 16, 255, 256, capacity - 1, capacity, capacity + 1, capacity * 3}) {
            byte[] source = new byte[size + 4];
            random.nextBytes(source);
            actual.write(source, 2, size); expected.write(source, 2, size);
        }
        actual.close(); expected.close();
        assertArrayEquals(expectedBytes.toByteArray(), wire.toByteArray());
        assertEquals(0, wire.closes);
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 8, 8192})
    void mixedSlicesSingleBytesAndFlushesPreserveExactOutput(int capacity) throws Exception {
        var wire = new RecordingOutput();
        var expected = new ByteArrayOutputStream();
        var buffered = new Http1OutputBuffer(wire, capacity);
        var random = new Random(612);
        for (int i = 0; i < 100; i++) {
            byte[] source = new byte[random.nextInt(capacity * 3 + 1) + 4];
            random.nextBytes(source);
            buffered.write(source, 2, source.length - 4);
            expected.write(source, 2, source.length - 4);
            buffered.write(i); expected.write(i);
            if (i % 7 == 0) {
                buffered.flush();
                assertArrayEquals(expected.toByteArray(), wire.toByteArray());
            }
        }
        buffered.close(); buffered.close();
        assertArrayEquals(expected.toByteArray(), wire.toByteArray());
        assertEquals(0, wire.closes);
        assertThrows(IOException.class, () -> buffered.write(1));
        assertThrows(IOException.class, () -> buffered.write(new byte[0]));
        assertThrows(IOException.class, buffered::flush);
    }

    @Test void aTransportBorrowKeepsBufferStorageStableUntilTheWriteReturns() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var wire = new ByteArrayOutputStream();
        var workers = Executors.newFixedThreadPool(2);
        try {
            OutputStream delayed = new OutputStream() {
                @Override public void write(int value) { fail("Expected array write"); }
                @Override public void write(byte[] source, int offset, int length) throws IOException {
                    byte[] before = source.clone();
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { throw new IOException(interrupted); }
                    assertArrayEquals(before, source);
                    wire.write(source, offset, length);
                }
            };
            var buffered = new Http1OutputBuffer(delayed, 8);
            buffered.write(new byte[]{1, 2});
            var writing = workers.submit(() -> { buffered.write(new byte[]{3, 4, 5, 6, 7, 8, 9, 10}); return null; });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var secondStarted = new CountDownLatch(1);
            var closing = workers.submit(() -> { secondStarted.countDown(); buffered.close(); return null; });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertFalse(writing.isDone()); assertFalse(closing.isDone());
            release.countDown();
            writing.get(5, TimeUnit.SECONDS); closing.get(5, TimeUnit.SECONDS);
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, wire.toByteArray());
        } finally { release.countDown(); workers.shutdownNow(); }
    }

    @Test void aFailedCloseIsTerminalAndDoesNotCloseTheConnection() throws Exception {
        IOException failure = new IOException("transport failure");
        var wire = new RecordingOutput() {
            @Override public void flush() throws IOException { throw failure; }
        };
        var buffered = new Http1OutputBuffer(wire, 8);
        buffered.write(new byte[]{1, 2, 3});
        assertSame(failure, assertThrows(IOException.class, buffered::close));
        buffered.close();
        assertThrows(IOException.class, () -> buffered.write(4));
        assertArrayEquals(new byte[]{1, 2, 3}, wire.toByteArray());
        assertEquals(0, wire.closes);
    }

    private static class RecordingOutput extends ByteArrayOutputStream {
        final List<Integer> writes = new ArrayList<>();
        int flushes;
        int closes;
        @Override public void write(byte[] source, int offset, int length) {
            writes.add(length); super.write(source, offset, length);
        }
        @Override public void flush() throws IOException { flushes++; }
        @Override public void close() { closes++; }
    }
}
