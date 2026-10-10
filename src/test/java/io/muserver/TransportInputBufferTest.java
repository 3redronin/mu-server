package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TransportInputBufferTest {
    @Test
    void offersAreBoundedAndCopiedAndCapacityNotificationsResumeAfterFullBuffers() throws Exception {
        AtomicInteger wakeups = new AtomicInteger();
        try (var input = new TransportInputBuffer(4, wakeups::incrementAndGet)) {
            byte[] source = "abcdef".getBytes(US_ASCII);
            ByteBuffer bytes = ByteBuffer.wrap(source);
            assertEquals(4, input.offer(bytes));
            assertEquals(0, input.offer(bytes));
            assertEquals(4, bytes.position());
            assertEquals(0, input.remainingCapacity());
            source[0] = 'X';
            assertEquals('a', input.read());
            assertEquals(1, wakeups.get());
            assertEquals('b', input.read());
            assertEquals(1, wakeups.get());
            assertEquals(2, input.offer(bytes));
            input.endOfInput();
            assertEquals("cdef", new String(input.readAllBytes(), US_ASCII));
            assertEquals(-1, input.read());
            assertEquals(0, input.remainingCapacity());
            assertThrows(IOException.class, () -> input.offer(ByteBuffer.wrap(new byte[1])));
        }
    }

    @Test
    void directAndReadOnlyFeedsAndReadOffsetsPreserveBytesAcrossWraparound() throws Exception {
        try (var input = new TransportInputBuffer(3, () -> { })) {
            ByteBuffer direct = ByteBuffer.allocateDirect(4).put("abcd".getBytes(US_ASCII)).flip();
            assertEquals(3, input.offer(direct));
            assertEquals('a', input.read());
            assertEquals(1, input.offer(direct.asReadOnlyBuffer()));
            byte[] target = new byte[7];
            assertEquals(3, input.read(target, 2, 4));
            assertArrayEquals(new byte[]{0, 0, 'b', 'c', 'd', 0, 0}, target);
            assertThrows(IndexOutOfBoundsException.class, () -> input.read(target, 8, 0));
            assertEquals(0, input.read(target, 0, 0));
        }
    }

    @Test
    void producerCanWakeReaderAndEofWakesAnEmptyReader() throws Exception {
        var tasks = Executors.newSingleThreadExecutor();
        try (var input = new TransportInputBuffer(2, () -> { })) {
            var first = tasks.submit(() -> { return input.read(); });
            assertEquals(1, input.offer(ByteBuffer.wrap(new byte[]{42})));
            assertEquals(42, first.get(2, TimeUnit.SECONDS));
            var end = tasks.submit(() -> { return input.read(); });
            input.endOfInput();
            assertEquals(-1, end.get(2, TimeUnit.SECONDS));
        } finally {
            tasks.shutdownNow();
            assertTrue(tasks.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void failureWakesReadersAndOverridesQueuedBytesAndEof() throws Exception {
        var tasks = Executors.newSingleThreadExecutor();
        try (var input = new TransportInputBuffer(2, () -> { })) {
            IOException failure = new IOException("aborted");
            var reader = tasks.submit(() -> assertThrows(IOException.class, input::read));
            input.fail(failure);
            assertSame(failure, reader.get(2, TimeUnit.SECONDS));
            input.close();
            assertSame(failure, assertThrows(IOException.class, input::read));
            assertSame(failure, assertThrows(IOException.class, () -> input.offer(ByteBuffer.allocate(1))));
        } finally {
            tasks.shutdownNow();
            assertTrue(tasks.awaitTermination(2, TimeUnit.SECONDS));
        }
        try (var input = new TransportInputBuffer(2, () -> { })) {
            input.offer(ByteBuffer.wrap(new byte[]{1, 2}));
            input.endOfInput();
            input.close();
            assertThrows(IOException.class, input::read);
        }
    }

    @Test
    void timeoutIsAReadFailureAndInterruptionPreservesTheInterruptFlag() throws Exception {
        try (var input = new TransportInputBuffer(2, () -> { })) {
            input.readTimeoutMillis(20);
            assertThrows(SocketTimeoutException.class, input::read);
            input.readTimeoutMillis(0);
            input.offer(ByteBuffer.wrap(new byte[]{7}));
            assertEquals(7, input.read());
            var tasks = Executors.newSingleThreadExecutor();
            try {
                CountDownLatch started = new CountDownLatch(1);
                var reader = tasks.submit(() -> {
                    Thread.currentThread().interrupt();
                    started.countDown();
                    assertThrows(InterruptedIOException.class, input::read);
                    return Thread.currentThread().isInterrupted();
                });
                assertTrue(started.await(2, TimeUnit.SECONDS));
                assertTrue(reader.get(2, TimeUnit.SECONDS));
            } finally {
                tasks.shutdownNow();
                assertTrue(tasks.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }
}
