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
    @ParameterizedTest @CsvSource({"1,16393", "8201,16393", "8201,97"})
    void borrowedDrainsHaveTheirOwnBudgetWithoutGrowingTheRing(int capacity, int drainLimit) throws Exception {
        var output = new TransportOutputBuffer(capacity, drainLimit, () -> {});
        var ring = TransportOutputBuffer.class.getDeclaredField("bytes");
        ring.setAccessible(true);
        assertEquals(capacity, ((byte[]) ring.get(output)).length);
        byte[] expected = new byte[40003];
        new Random(872).nextBytes(expected);
        var write = output.asynchronousWriter().write(ByteBuffer.wrap(expected));
        var sink = new TransportOutputBufferTest.Sink(Integer.MAX_VALUE) {
            @Override public int write(ByteBuffer data) throws IOException {
                assertSame(expected, data.array());
                return super.write(data);
            }
        };
        int[] budgets = {65536, 1, 8192, 97, 16393};
        int consumed = 0, turn = 0;
        while (!write.isDone()) {
            assertEquals(Math.min(drainLimit, expected.length - consumed), output.pendingBytes());
            int budget = budgets[turn++ % budgets.length];
            int count = Math.min(expected.length - consumed, Math.min(drainLimit, budget));
            assertEquals(count, output.drainTo(sink, budget));
            consumed += count;
            assertEquals(consumed == expected.length, write.isDone());
        }
        write.get();
        assertArrayEquals(expected, sink.bytes.toByteArray());
        assertEquals(capacity, ((byte[]) ring.get(output)).length);
    }

    @Test void asyncBudgetDoesNotEnlargeBlockingRingDrains() throws Exception {
        var output = new TransportOutputBuffer(7, 16393, () -> {});
        byte[] expected = new byte[101];
        new Random(72).nextBytes(expected);
        var worker = Executors.newSingleThreadExecutor();
        var sink = new TransportOutputBufferTest.Sink(Integer.MAX_VALUE) {
            @Override public int write(ByteBuffer data) throws IOException {
                assertTrue(data.remaining() <= 7);
                return super.write(data);
            }
        };
        try {
            var write = worker.submit(() -> { output.write(expected); return null; });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!write.isDone()) {
                assertTrue(System.nanoTime() < deadline);
                assertTrue(output.pendingBytes() <= 7);
                if (output.drainTo(sink, 16393) == 0) Thread.sleep(1);
            }
            write.get();
            assertArrayEquals(expected, sink.bytes.toByteArray());
        } finally { output.fail(new IOException("Test finished")); worker.shutdownNow(); }
    }

    @Test void asyncBudgetMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new TransportOutputBuffer(7, 0, () -> {}));
        assertThrows(IllegalArgumentException.class, () -> new TransportOutputBuffer(7, -1, () -> {}));
    }

    @ParameterizedTest @CsvSource({"1,1", "1,8192", "7,1", "7,3", "7,8192", "8192,1", "8192,3", "8192,8192",
        "8201,1", "8201,8192", "8201,8201"})
    void partialDrainsUseBoundedChunksAndCompleteAfterTheLastByte(int capacity, int budget) throws Exception {
        var output = new TransportOutputBuffer(capacity, () -> {});
        var writer = output.asynchronousWriter();
        byte[] bytes = new byte[20003];
        new Random(123).nextBytes(bytes);
        ByteBuffer source = ByteBuffer.allocateDirect(bytes.length + 4).position(2).put(bytes).flip().position(2).asReadOnlyBuffer();
        var progress = new TransportWriteProgress(() -> {});
        var completed = writer.write(source, progress);
        assertFalse(completed.isDone());
        var received = new ByteArrayOutputStream();
        var sink = new WritableByteChannel() {
            int turns;
            @Override public int write(ByteBuffer data) {
                assertTrue(data.isDirect(), "Drain must borrow the direct source without a heap copy");
                assertTrue(data.isReadOnly());
                assertTrue(data.remaining() <= Math.min(capacity, budget));
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
            assertTrue(progress.reached(received.size()));
            assertFalse(progress.reached(received.size() + 1), "Only sink consumption may publish progress");
            if (received.size() < bytes.length) assertFalse(completed.isDone());
        }
        completed.get();
        assertArrayEquals(bytes, received.toByteArray());
        assertEquals(2, source.position(), "The transport uses its own source view");
        assertEquals(0, output.pendingBytes());
        assertTrue(writer.write(ByteBuffer.allocate(0)).isDone());
    }

    @Test
    void slicedHeapSourceIsBorrowedWithoutChangingCallerPositionLimitOrMark() throws Exception {
        byte[] storage = new byte[37];
        new Random(729).nextBytes(storage);
        ByteBuffer source = ByteBuffer.wrap(storage, 3, 29).slice().position(2).mark().limit(27);
        var output = new TransportOutputBuffer(7, () -> {});
        var writer = output.asynchronousWriter();
        var write = writer.write(source);
        var sink = new TransportOutputBufferTest.Sink(3) {
            @Override public int write(ByteBuffer data) throws IOException {
                assertSame(storage, data.array(), "The sink must see the borrowed backing storage");
                assertTrue(data.remaining() <= 7);
                return super.write(data);
            }
        };
        while (!write.isDone()) output.drainTo(sink, 11);
        write.get();
        assertArrayEquals(java.util.Arrays.copyOfRange(storage, 5, 30), sink.bytes.toByteArray());
        assertEquals(2, source.position());
        assertEquals(27, source.limit());
        source.reset();
        assertEquals(2, source.position());
        // Completion returns storage ownership: reusing the same source must send its new contents.
        java.util.Arrays.fill(storage, (byte) 99);
        sink.bytes.reset();
        var next = writer.write(source);
        while (!next.isDone()) output.drainTo(sink, 11);
        next.get();
        assertArrayEquals(java.util.Arrays.copyOfRange(storage, 5, 30), sink.bytes.toByteArray());
    }

    @Test
    void progressNotificationRunsOutsideTheRingLockAndDoesNotAcknowledgeTheRemainingSource() throws Exception {
        var output = new TransportOutputBuffer(7, () -> {});
        var check = Executors.newSingleThreadExecutor();
        try {
            var notifications = new java.util.concurrent.atomic.AtomicInteger();
            var progress = new TransportWriteProgress(() -> {
                try { check.submit(output::pendingBytes).get(2, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError("Progress notification held ring storage", e); }
                notifications.incrementAndGet();
            });
            progress.awaitOffset(3);
            var write = output.asynchronousWriter().write(ByteBuffer.wrap(new byte[19]), progress);
            var sink = new TransportOutputBufferTest.Sink(2);
            output.drainTo(sink, 7);
            assertEquals(0, notifications.get());
            output.drainTo(sink, 7);
            assertEquals(1, notifications.get());
            assertTrue(progress.reached(4));
            assertFalse(progress.reached(5));
            assertFalse(write.isDone());
            output.fail(new IOException("Abort after a drained prefix"));
            assertThrows(ExecutionException.class, write::get);
            assertFalse(progress.reached(5));
        } finally { check.shutdownNow(); }
    }

    @ParameterizedTest @CsvSource({"7,7", "7,25", "8192,8192", "8192,20003"})
    void anEmptyRingDoesNotSplitTheNextWriteAtThePreviousOffset(int capacity, int length) throws Exception {
        var output = new TransportOutputBuffer(capacity, () -> {});
        var writer = output.asynchronousWriter();
        var sink = new TransportOutputBufferTest.Sink(capacity);
        var prefix = writer.write(ByteBuffer.wrap(new byte[]{1, 2, 3}));
        assertEquals(3, output.drainTo(sink, capacity));
        prefix.get();
        byte[] bytes = new byte[length];
        new Random(981).nextBytes(bytes);
        var write = writer.write(ByteBuffer.wrap(bytes));
        for (int remaining = length; remaining > 0; remaining -= capacity) {
            assertFalse(write.isDone());
            assertEquals(Math.min(remaining, capacity), output.drainTo(sink, capacity),
                "An empty ring should admit the next contiguous prefix without a needless split");
        }
        write.get();
        var expected = new ByteArrayOutputStream();
        expected.write(new byte[]{1, 2, 3}); expected.write(bytes);
        assertArrayEquals(expected.toByteArray(), sink.bytes.toByteArray());
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
    void abortCannotReleaseBorrowedSourceWhileTheSinkStillUsesIt() throws Exception {
        var output = new TransportOutputBuffer(4, () -> {});
        var writer = output.asynchronousWriter();
        byte[] bytes = new byte[100];
        var completed = writer.write(ByteBuffer.wrap(bytes));
        var tasks = Executors.newFixedThreadPool(2);
        CountDownLatch writing = new CountDownLatch(1), release = new CountDownLatch(1), aborting = new CountDownLatch(1);
        var sink = new WritableByteChannel() {
            @Override public int write(ByteBuffer source) throws IOException {
                assertSame(bytes, source.array());
                writing.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("Test did not release sink"); }
                catch (InterruptedException e) { throw new IOException(e); }
                assertFalse(completed.isDone(), "Ownership cannot return while the sink accesses storage");
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

    @Test
    void sinkFailureAfterAdvancingItsViewReleasesOwnershipWithoutAcknowledgingBytes() throws Exception {
        var output = new TransportOutputBuffer(7, () -> {});
        byte[] bytes = {1, 2, 3, 4, 5};
        ByteBuffer source = ByteBuffer.wrap(bytes);
        var progress = new TransportWriteProgress(() -> {});
        var write = output.asynchronousWriter().write(source, progress);
        IOException failure = new IOException("Failed after partial sink access");
        var sink = new TransportOutputBufferTest.Sink(2) {
            @Override public int write(ByteBuffer data) throws IOException {
                super.write(data);
                throw failure;
            }
        };
        assertSame(failure, assertThrows(IOException.class, () -> output.drainTo(sink, 7)));
        assertSame(failure, assertThrows(ExecutionException.class, write::get).getCause());
        assertFalse(progress.reached(1));
        assertEquals(0, source.position());
        java.util.Arrays.fill(bytes, (byte) 99);
        assertSame(failure, assertThrows(IOException.class, () -> output.drainTo(sink, 7)));
        assertArrayEquals(new byte[]{1, 2}, sink.bytes.toByteArray());
        assertEquals(0, output.pendingBytes());
    }
}
