package io.muserver;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class Http2WriteBatchTest {
    @Test
    void buffersSmallWritesAndFlushesAtTheByteLimit() throws Exception {
        var writes = new ArrayList<Integer>();
        var wire = new ByteArrayOutputStream();
        var callbacks = new ArrayList<String>();
        var output = new OutputStream() {
            @Override public void write(int value) { fail("Expected bulk output"); }
            @Override public void write(byte[] bytes, int offset, int length) {
                writes.add(length);
                wire.write(bytes, offset, length);
            }
            @Override public void flush() { callbacks.add("flush"); }
        };
        var batch = new Http2WriteBatch(output, () -> callbacks.add("write"), () -> callbacks.add("complete"));
        for (int i = 0; i < Http2WriteBatch.MAX_BYTES + 1; i++) batch.write(i);
        assertEquals(List.of(Http2WriteBatch.MAX_BYTES), writes);
        assertEquals(List.of("write", "flush", "complete"), callbacks);
        assertEquals(Http2WriteBatch.MAX_BYTES + 1, batch.acceptedBytes());
        assertEquals(Http2WriteBatch.MAX_BYTES, batch.flushedBytes());
        batch.flush();
        assertEquals(List.of(Http2WriteBatch.MAX_BYTES, 1), writes);
        assertEquals(batch.acceptedBytes(), batch.flushedBytes());
        byte[] bytes = wire.toByteArray();
        for (int i = 0; i < bytes.length; i++) assertEquals((byte) i, bytes[i]);
    }

    @Test
    void largeWritesBypassCopyingAfterFlushingPreviousBytes() throws Exception {
        var large = new byte[Http2WriteBatch.MAX_BYTES];
        var identities = new ArrayList<byte[]>();
        var order = new ArrayList<String>();
        var output = new OutputStream() {
            @Override public void write(int value) { fail("Expected bulk output"); }
            @Override public void write(byte[] bytes, int offset, int length) {
                identities.add(bytes);
                order.add("write:" + length);
            }
            @Override public void flush() { order.add("flush"); }
        };
        var batch = new Http2WriteBatch(output, () -> {}, () -> order.add("complete"));
        batch.write(1);
        batch.write(large);
        assertEquals(List.of("write:1", "flush", "complete", "write:" + Http2WriteBatch.MAX_BYTES), order);
        assertSame(large, identities.get(1));
        assertEquals(1, batch.flushedBytes());
        batch.flush();
        assertEquals(Http2WriteBatch.MAX_BYTES + 1, batch.flushedBytes());
    }

    @Test
    void largeBulkWritesBypassAnEmptyBufferButCombineWithPrecedingBytes() throws Exception {
        var bulk = new byte[8192];
        var writes = new ArrayList<Integer>();
        var output = new OutputStream() {
            @Override public void write(int value) { fail("Expected bulk output"); }
            @Override public void write(byte[] bytes, int offset, int length) { writes.add(length); }
        };
        var batch = new Http2WriteBatch(output, () -> {}, () -> {});
        batch.write(bulk);
        assertEquals(List.of(bulk.length), writes);
        assertEquals(bulk.length, batch.writtenBytes());
        assertEquals(0, batch.flushedBytes());
        batch.flush();
        batch.write(1);
        batch.write(bulk);
        assertEquals(List.of(bulk.length), writes);
        batch.flush();
        assertEquals(List.of(bulk.length, bulk.length + 1), writes);
    }

    @Test
    void defaultResponseChunkAndFrameHeaderShareOneWrite() throws Exception {
        var writes = new ArrayList<Integer>();
        var wire = new ByteArrayOutputStream() {
            @Override public void write(byte[] bytes, int offset, int length) {
                writes.add(length);
                super.write(bytes, offset, length);
            }
        };
        var batch = new Http2WriteBatch(wire, () -> {}, () -> {});
        var header = new byte[9];
        var body = new byte[8192];
        java.util.Arrays.fill(header, (byte) 1);
        java.util.Arrays.fill(body, (byte) 2);
        batch.write(header);
        batch.write(body);
        assertTrue(writes.isEmpty());
        batch.flush();
        assertEquals(List.of(8201), writes);
        var expected = new ByteArrayOutputStream();
        expected.write(header);
        expected.write(body);
        assertArrayEquals(expected.toByteArray(), wire.toByteArray());
    }

    @Test
    void bypassThresholdRemainsEightKiBWhenTheBatchLimitChanges() throws Exception {
        var writes = new ArrayList<Integer>();
        var output = new OutputStream() {
            @Override public void write(int value) { fail("Expected bulk output"); }
            @Override public void write(byte[] bytes, int offset, int length) { writes.add(length); }
        };
        var batch = new Http2WriteBatch(output, () -> {}, () -> {});
        batch.write(new byte[8191]);
        assertTrue(writes.isEmpty());
        batch.flush();
        assertEquals(List.of(8191), writes);
        batch.write(new byte[8192]);
        assertEquals(List.of(8191, 8192), writes);
    }

    @Test
    void failedFlushDoesNotPublishCompletion() throws Exception {
        var completed = new ArrayList<Long>();
        var output = new ByteArrayOutputStream() {
            @Override public void flush() throws IOException { throw new IOException("flush failed"); }
        };
        var batch = new Http2WriteBatch(output, () -> {}, () -> completed.add(1L));
        batch.write(new byte[10]);
        assertEquals("flush failed", assertThrows(IOException.class, batch::flush).getMessage());
        assertEquals(10, batch.writtenBytes());
        assertEquals(0, batch.flushedBytes());
        assertTrue(completed.isEmpty());
    }
}
