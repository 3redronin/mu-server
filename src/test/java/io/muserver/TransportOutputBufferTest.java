package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TransportOutputBufferTest {
    @Test
    void queuedAndPartiallyDrainedWritesDoNotCompleteUntilTheWholeWriteReachesTheSink() throws Exception {
        var tasks = Executors.newFixedThreadPool(2);
        Semaphore ready = new Semaphore(0);
        var output = new TransportOutputBuffer(4, ready::release);
        var sink = new Sink(2);
        try {
            byte[] source = "abcd".getBytes(US_ASCII);
            var write = tasks.submit(() -> { output.write(source); return null; });
            assertTrue(ready.tryAcquire(2, TimeUnit.SECONDS));
            assertEquals(4, output.pendingBytes());
            assertFalse(write.isDone());
            source[0] = 'X'; // Admission owns a copy, though callers still must await write completion.
            sink.limit = 0;
            assertEquals(0, output.drainTo(sink, 4));
            assertFalse(write.isDone());
            sink.limit = 2;
            assertEquals(2, output.drainTo(sink, 4));
            assertFalse(write.isDone());
            var flush = tasks.submit(() -> { output.flush(); return null; });
            assertEquals(2, output.drainTo(sink, 4));
            write.get(2, TimeUnit.SECONDS);
            flush.get(2, TimeUnit.SECONDS);
            assertEquals("abcd", sink.bytes.toString(US_ASCII));
            output.close();
            output.close();
            assertThrows(IOException.class, () -> output.write(1));
        } finally {
            output.fail(new IOException("Test ended"));
            tasks.shutdownNow();
            assertTrue(tasks.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void largeWritesWrapWithinTheBoundAndConcurrentWritesStayContiguous() throws Exception {
        var tasks = Executors.newFixedThreadPool(2);
        Semaphore ready = new Semaphore(0);
        var output = new TransportOutputBuffer(7, ready::release);
        var sink = new Sink(3);
        try {
            String first = "a".repeat(10003);
            var write1 = tasks.submit(() -> { output.write(first.getBytes(US_ASCII)); return null; });
            assertTrue(ready.tryAcquire(2, TimeUnit.SECONDS));
            var write2 = tasks.submit(() -> { output.write("END".getBytes(US_ASCII)); return null; });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!write1.isDone() || !write2.isDone()) {
                assertTrue(System.nanoTime() < deadline, "Writes did not complete");
                assertTrue(output.pendingBytes() <= 7);
                if (output.drainTo(sink, 2) == 0) ready.tryAcquire(1, TimeUnit.MILLISECONDS);
            }
            write1.get();
            write2.get();
            assertEquals(first + "END", sink.bytes.toString(US_ASCII));
        } finally {
            output.fail(new IOException("Test ended"));
            tasks.shutdownNow();
            assertTrue(tasks.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void abortReleasesCapacityWaitersCompletionWaitersAndQueuedWriters() throws Exception {
        for (int length : new int[]{2, 20}) {
            var tasks = Executors.newFixedThreadPool(3);
            Semaphore ready = new Semaphore(0);
            var output = new TransportOutputBuffer(4, ready::release);
            try {
                var write = tasks.submit(() -> assertThrows(IOException.class, () -> output.write(new byte[length])));
                assertTrue(ready.tryAcquire(2, TimeUnit.SECONDS));
                var next = tasks.submit(() -> assertThrows(IOException.class, () -> output.write(1)));
                var close = tasks.submit(() -> assertThrows(IOException.class, output::close));
                IOException cause = new IOException("aborted");
                output.fail(cause);
                assertSame(cause, write.get(2, TimeUnit.SECONDS));
                assertSame(cause, next.get(2, TimeUnit.SECONDS));
                assertSame(cause, close.get(2, TimeUnit.SECONDS));
                assertEquals(0, output.pendingBytes());
            } finally {
                output.fail(new IOException("Test ended"));
                tasks.shutdownNow();
                assertTrue(tasks.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void failedSinkWakesTheWriterAndMakesAllLaterWritesFail() throws Exception {
        var tasks = Executors.newSingleThreadExecutor();
        Semaphore ready = new Semaphore(0);
        var output = new TransportOutputBuffer(4, ready::release);
        try {
            var write = tasks.submit(() -> assertThrows(IOException.class, () -> output.write(new byte[4])));
            assertTrue(ready.tryAcquire(2, TimeUnit.SECONDS));
            IOException cause = new IOException("peer reset");
            var sink = new Sink(1) {
                @Override public int write(ByteBuffer src) throws IOException { throw cause; }
            };
            assertSame(cause, assertThrows(IOException.class, () -> output.drainTo(sink, 4)));
            assertSame(cause, write.get(2, TimeUnit.SECONDS));
            assertSame(cause, assertThrows(IOException.class, () -> output.write(1)));
        } finally {
            output.fail(new IOException("Test ended"));
            tasks.shutdownNow();
            assertTrue(tasks.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptAfterAdmissionFailsTheStreamAndPreservesTheFlag() throws Exception {
        var tasks = Executors.newSingleThreadExecutor();
        Semaphore ready = new Semaphore(0);
        AtomicReference<Thread> writer = new AtomicReference<>();
        var output = new TransportOutputBuffer(4, ready::release);
        try {
            var write = tasks.submit(() -> {
                writer.set(Thread.currentThread());
                assertThrows(InterruptedIOException.class, () -> output.write(new byte[20]));
                return Thread.currentThread().isInterrupted();
            });
            assertTrue(ready.tryAcquire(2, TimeUnit.SECONDS));
            writer.get().interrupt();
            assertTrue(write.get(2, TimeUnit.SECONDS));
            assertEquals(0, output.pendingBytes());
            assertThrows(InterruptedIOException.class, () -> output.write(1));
        } finally {
            output.fail(new IOException("Test ended"));
            tasks.shutdownNow();
            assertTrue(tasks.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    static class Sink implements WritableByteChannel {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int limit;
        Sink(int limit) { this.limit = limit; }
        @Override public int write(ByteBuffer src) throws IOException {
            int count = Math.min(limit, src.remaining());
            byte[] data = new byte[count];
            src.get(data);
            bytes.write(data);
            return count;
        }
        @Override public boolean isOpen() { return true; }
        @Override public void close() { }
    }
}
