package io.muserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class AsyncTransportOutputStreamTest {
    @Test void interruptionAbortsButWaitsForTheBorrowToReturn() throws Exception {
        var entered = new CountDownLatch(1);
        var aborted = new CountDownLatch(1);
        var io = new CompletableFuture<Void>();
        var borrowed = new AtomicReference<ByteBuffer>();
        var stream = new AsyncTransportOutputStream(bytes -> {
            borrowed.set(bytes); entered.countDown(); return io;
        }, aborted::countDown);
        var outcome = new CompletableFuture<Throwable>();
        var interruptedAfterReturn = new CompletableFuture<Boolean>();
        byte[] bytes = {0, 1, 2, 3};
        Thread writer = new Thread(() -> {
            try { stream.write(bytes, 1, 2); outcome.complete(null); }
            catch (Throwable failure) { outcome.complete(failure); }
            finally { interruptedAfterReturn.complete(Thread.currentThread().isInterrupted()); }
        });
        writer.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertSame(bytes, borrowed.get().array());
            assertEquals(1, borrowed.get().position());
            assertEquals(2, borrowed.get().remaining());
            writer.interrupt();
            assertTrue(aborted.await(3, TimeUnit.SECONDS));
            assertFalse(outcome.isDone());
            assertFalse(io.isCancelled());
            io.completeExceptionally(new IOException("transport abort acknowledged"));
            assertInstanceOf(InterruptedIOException.class, outcome.get(3, TimeUnit.SECONDS));
            assertTrue(interruptedAfterReturn.get(3, TimeUnit.SECONDS));
        } finally { io.complete(null); writer.join(3000); }
    }

    @Test void interruptionBeforeAcquiringTheWriterDoesNotAbortSomebodyElsesBorrow() throws Exception {
        var entered = new CountDownLatch(1);
        var io = new CompletableFuture<Void>();
        var aborts = new AtomicInteger();
        var stream = new AsyncTransportOutputStream(bytes -> {
            entered.countDown(); return io;
        }, aborts::incrementAndGet);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> first = executor.submit(() -> { stream.write(1); return null; });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> stream.write(2));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
            assertEquals(0, aborts.get());
            assertFalse(first.isDone());
            io.complete(null);
            first.get(3, TimeUnit.SECONDS);
        } finally { io.complete(null); executor.shutdownNow(); }
    }

    @Test void queuedPublicWritesSerializeTheirBorrows() throws Exception {
        var pending = new LinkedBlockingQueue<CompletableFuture<Void>>();
        var calls = new AtomicInteger();
        var active = new AtomicReference<CompletableFuture<Void>>();
        var stream = new AsyncTransportOutputStream(bytes -> {
            calls.incrementAndGet();
            var completion = new CompletableFuture<Void>();
            active.set(completion);
            pending.add(completion);
            return completion;
        }, () -> active.get().completeExceptionally(new IOException("Test writer aborted")));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CompletableFuture<Void> firstIo = null, secondIo = null;
        try {
            Future<?> first = executor.submit(() -> { stream.write(1); return null; });
            firstIo = pending.poll(3, TimeUnit.SECONDS);
            assertNotNull(firstIo);
            var secondStarted = new CountDownLatch(1);
            Future<?> second = executor.submit(() -> { secondStarted.countDown(); stream.write(2); return null; });
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            firstIo.complete(null);
            first.get(3, TimeUnit.SECONDS);
            secondIo = pending.poll(3, TimeUnit.SECONDS);
            assertNotNull(secondIo);
            assertFalse(second.isDone());
            secondIo.complete(null);
            second.get(3, TimeUnit.SECONDS);
        } finally {
            if (firstIo != null) firstIo.complete(null);
            if (secondIo != null) secondIo.complete(null);
            CompletableFuture<Void> io;
            while ((io = pending.poll()) != null) io.complete(null);
            executor.shutdownNow();
        }
    }

    @Test void synchronousFailureAndCloseKeepTheirStreamSemantics() throws Exception {
        IOException failed = new IOException("transport failed");
        var stream = new AsyncTransportOutputStream(bytes -> CompletableFuture.failedFuture(failed), () -> {});
        assertSame(failed, assertThrows(IOException.class, () -> stream.write(1)));
        assertSame(failed, assertThrows(IOException.class, stream::flush));
        var emptyWrites = new AtomicInteger();
        var closable = new AsyncTransportOutputStream(bytes -> {
            assertFalse(bytes.hasRemaining());
            emptyWrites.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }, () -> {});
        closable.flush();
        closable.close();
        closable.close();
        assertEquals(2, emptyWrites.get());
        assertThrows(IOException.class, () -> closable.write(1));
    }
}
