package io.muserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AsyncResponseOutputTest {
    private final ExecutorService application = Executors.newSingleThreadExecutor(task -> new Thread(task, "application"));
    private final ExecutorService io = Executors.newCachedThreadPool();
    private final Mu3ServerImpl server = (Mu3ServerImpl) MuServerBuilder.httpServer().withHandlerExecutor(application).start();

    @AfterEach void stop() { server.stop(); application.shutdownNow(); io.shutdownNow(); }

    @Test void acceptedWritesDrainInOrderWithoutWaitingForCallbacks() throws Exception {
        var firstStarted = new CountDownLatch(1);
        var releaseOutput = new CountDownLatch(1);
        var releaseCallback = new CountDownLatch(1);
        var firstCallback = new CountDownLatch(1);
        var allCallbacks = new CountDownLatch(2);
        var bytes = new CopyOnWriteArrayList<Byte>();
        var delivered = new CopyOnWriteArrayList<Integer>();
        var output = new AsyncResponseOutput(io, buffer -> {
            firstStarted.countDown();
            releaseOutput.await();
            bytes.add(buffer.get());
        }, active -> { }, new SerialApplicationTasks(server));
        var first = output.write(ByteBuffer.wrap(new byte[]{1}), error -> {
            assertNull(error);
            assertEquals("application", Thread.currentThread().getName());
            delivered.add(1);
            firstCallback.countDown();
            releaseCallback.await();
            allCallbacks.countDown();
        });
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
        var second = output.write(ByteBuffer.wrap(new byte[]{2}), error -> {
            assertNull(error);
            delivered.add(2);
            allCallbacks.countDown();
        });
        output.complete(null);
        assertThrows(ExecutionException.class, () -> output.write(ByteBuffer.allocate(1), null).get());
        try {
            releaseOutput.countDown();
            assertTrue(firstCallback.await(5, TimeUnit.SECONDS));
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            output.completion().get(5, TimeUnit.SECONDS);
            assertEquals(List.of((byte) 1, (byte) 2), bytes);
            assertEquals(List.of(1), delivered);
        } finally { releaseCallback.countDown(); releaseOutput.countDown(); }
        assertTrue(allCallbacks.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(1, 2), delivered);
    }

    @Test void failureKeepsActiveBufferOwnedUntilIoAcknowledgesAbort() throws Exception {
        var started = new CountDownLatch(1);
        var aborted = new CountDownLatch(1);
        var acknowledge = new CountDownLatch(1);
        var delivered = new CopyOnWriteArrayList<Integer>();
        var callbacksDone = new CountDownLatch(3);
        IOException expected = new IOException("stop output");
        var output = new AsyncResponseOutput(io, buffer -> {
            started.countDown();
            acknowledge.await();
            throw expected;
        }, active -> aborted.countDown(), new SerialApplicationTasks(server));
        var first = output.write(ByteBuffer.allocate(10), error -> { delivered.add(1); callbacksDone.countDown(); });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        var second = output.write(ByteBuffer.allocate(10), error -> { delivered.add(2); callbacksDone.countDown(); });
        var third = output.write(ByteBuffer.allocate(10), error -> { delivered.add(3); callbacksDone.countDown(); });
        try {
            output.complete(expected);
            assertTrue(aborted.await(5, TimeUnit.SECONDS));
            assertFalse(first.isDone());
            assertFalse(output.completion().isDone());
            acknowledge.countDown();
            for (Future<?> future : List.of(first, second, third, output.completion())) {
                assertSame(expected, assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS)).getCause());
            }
            assertTrue(callbacksDone.await(5, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2, 3), delivered);
        } finally { acknowledge.countDown(); }
    }

    @Test void cancellingTheReturnedFutureWaitsForBufferRelease() throws Exception {
        var started = new CountDownLatch(1);
        var aborted = new CountDownLatch(1);
        var acknowledge = new CountDownLatch(1);
        var output = new AsyncResponseOutput(io, buffer -> {
            started.countDown();
            acknowledge.await();
        }, active -> aborted.countDown(), new SerialApplicationTasks(server));
        Future<?> write = output.write(ByteBuffer.allocate(1), null);
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Future<Boolean> cancellation = io.submit(() -> write.cancel(true));
        try {
            assertTrue(aborted.await(5, TimeUnit.SECONDS));
            assertFalse(write.isDone());
            assertFalse(cancellation.isDone());
            acknowledge.countDown();
            assertTrue(cancellation.get(5, TimeUnit.SECONDS));
            assertTrue(write.isCancelled());
        } finally { acknowledge.countDown(); }
    }
}
