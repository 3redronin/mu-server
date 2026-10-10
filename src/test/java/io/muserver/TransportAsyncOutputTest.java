package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Random;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TransportAsyncOutputTest {
    @ParameterizedTest @CsvSource({"1,1", "1,8192", "7,1", "7,3", "7,8192", "8192,1", "8192,3", "8192,8192"})
    void partialDrainsCopyOnlyBoundedChunksAndCompleteAfterTheLastByte(int capacity, int budget) throws Exception {
        var output = new TransportOutputBuffer(capacity, () -> {});
        var writer = output.asynchronousWriter();
        byte[] bytes = new byte[20003];
        new Random(123).nextBytes(bytes);
        ByteBuffer source = ByteBuffer.allocateDirect(bytes.length + 4).position(2).put(bytes).flip().position(2).asReadOnlyBuffer();
        var completed = writer.write(source);
        assertFalse(completed.isDone());
        var received = new ByteArrayOutputStream();
        var sink = new WritableByteChannel() {
            int turns;
            @Override public int write(ByteBuffer data) {
                if (++turns % 5 == 0) return 0;
                int count = Math.min(11, data.remaining());
                byte[] copy = new byte[count];
                data.get(copy); received.writeBytes(copy); return count;
            }
            @Override public boolean isOpen() { return true; }
            @Override public void close() { }
        };
        int turns = 0;
        while (!completed.isDone()) {
            assertTrue(++turns < 100000);
            assertTrue(output.pendingBytes() <= capacity);
            output.drainTo(sink, budget);
            if (received.size() < bytes.length) assertFalse(completed.isDone());
        }
        completed.get();
        assertArrayEquals(bytes, received.toByteArray());
        assertEquals(2, source.position(), "The transport uses its own source view");
        assertEquals(0, output.pendingBytes());
        assertTrue(writer.write(ByteBuffer.allocate(0)).isDone());
    }

    @Test
    void exclusiveWriterRejectsOverlapAndModeMixingWithoutLosingTheFirstWrite() throws Exception {
        var output = new TransportOutputBuffer(2, () -> {});
        var writer = output.asynchronousWriter();
        var first = writer.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        assertThrows(IllegalStateException.class, () -> writer.write(ByteBuffer.wrap(new byte[]{99})));
        assertThrows(IllegalStateException.class, () -> writer.write(ByteBuffer.allocate(0)));
        assertThrows(IllegalStateException.class, output::asynchronousWriter);
        assertThrows(IllegalStateException.class, () -> output.write(99));
        assertThrows(IllegalStateException.class, output::flush);
        var sink = new TransportOutputBufferTest.Sink(1);
        while (!first.isDone()) output.drainTo(sink, 2);
        first.get();
        var second = writer.write(ByteBuffer.wrap(new byte[]{4}));
        while (!second.isDone()) output.drainTo(sink, 2);
        second.get();
        assertArrayEquals(new byte[]{1, 2, 3, 4}, sink.bytes.toByteArray());
    }

    @ParameterizedTest @CsvSource({"false,false", "true,false", "true,true"})
    void failuresAreStickyAndFutureCallbacksRunOutsideTheRingLock(boolean fromSink, boolean unchecked) throws Exception {
        var output = new TransportOutputBuffer(2, () -> {});
        var writer = output.asynchronousWriter();
        var completed = writer.write(ByteBuffer.wrap(new byte[100]));
        var check = Executors.newSingleThreadExecutor();
        try {
            var callback = completed.handle((ignored, failure) -> {
                try { return check.submit(output::pendingBytes).get(2, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError("Completion ran while ring storage was locked", e); }
            });
            IOException error = new IOException("Injected output failure");
            if (fromSink) {
                var sink = new WritableByteChannel() {
                    @Override public int write(ByteBuffer source) throws IOException {
                        if (unchecked) throw new IllegalStateException("Broken channel", error);
                        throw error;
                    }
                    @Override public boolean isOpen() { return true; }
                    @Override public void close() { }
                };
                assertThrows(Exception.class, () -> output.drainTo(sink, 1));
            } else output.fail(error);
            assertEquals(0, callback.get());
            Throwable failure = assertThrows(ExecutionException.class, completed::get).getCause();
            if (unchecked) assertInstanceOf(IllegalStateException.class, failure.getCause());
            else assertSame(error, failure);
            output.fail(new IOException("second failure"));
            assertSame(failure, assertThrows(IOException.class, () -> writer.write(ByteBuffer.allocate(0))));
        } finally { check.shutdownNow(); }
    }

    @Test
    void abortCannotReleaseBorrowedSourceWhileTheSinkStillUsesTheRing() throws Exception {
        var output = new TransportOutputBuffer(4, () -> {});
        var writer = output.asynchronousWriter();
        byte[] bytes = new byte[100];
        var completed = writer.write(ByteBuffer.wrap(bytes));
        var tasks = Executors.newFixedThreadPool(2);
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1), aborting = new CountDownLatch(1);
        var sink = new WritableByteChannel() {
            @Override public int write(ByteBuffer source) throws IOException {
                writing.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Test did not release sink"); }
                catch (InterruptedException e) { throw new IOException(e); }
                int count = source.remaining(); source.position(source.limit()); return count;
            }
            @Override public boolean isOpen() { return true; }
            @Override public void close() { }
        };
        try {
            Future<?> drain = tasks.submit(() -> output.drainTo(sink, 4));
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            Future<?> abort = tasks.submit(() -> { aborting.countDown(); output.fail(new IOException("aborted")); });
            assertTrue(aborting.await(2, TimeUnit.SECONDS));
            assertFalse(completed.isDone());
            release.countDown();
            drain.get(2, TimeUnit.SECONDS); abort.get(2, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, completed::get);
            java.util.Arrays.fill(bytes, (byte) 99); // Returned source ownership is now safe to reuse.
            assertEquals(0, output.pendingBytes());
        } finally { release.countDown(); tasks.shutdownNow(); }
    }
}
