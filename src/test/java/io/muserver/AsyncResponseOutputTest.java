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

    @Test void exchangeFailureWaitsForAbortEvenWhenTheQueuedIoRunnerFindsNoWrites() throws Exception {
        var queuedIo = new LinkedBlockingQueue<Runnable>();
        var abortEntered = new CountDownLatch(1);
        var releaseAbort = new CountDownLatch(1);
        IOException expected = new IOException("cancel queued output");
        var output = new AsyncResponseOutput(queuedIo::add, buffer -> fail("Cancelled output must not start"), active -> {
            abortEntered.countDown();
            try { releaseAbort.await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }, new SerialApplicationTasks(server));
        Future<?> write = output.write(ByteBuffer.allocate(1), null);
        Future<?> cancelling = io.submit(() -> output.complete(expected));
        try {
            assertTrue(abortEntered.await(5, TimeUnit.SECONDS));
            queuedIo.remove().run();
            assertFalse(output.completion().isDone());
            assertFalse(write.isDone());
            releaseAbort.countDown();
            cancelling.get(5, TimeUnit.SECONDS);
            assertSame(expected, assertThrows(ExecutionException.class, () -> output.completion().get()).getCause());
        } finally { releaseAbort.countDown(); }
    }

    @Test void finishedFuturesDoNotRetainThePayloadCallbackOrResponse() throws Exception {
        var queuedIo = new java.util.ArrayDeque<Runnable>();
        var queuedCallbacks = new java.util.ArrayDeque<Runnable>();
        var callbackExecutor = new java.util.concurrent.AbstractExecutorService() {
            public void execute(Runnable task) { queuedCallbacks.add(task); }
            public void shutdown() { }
            public List<Runnable> shutdownNow() { return List.of(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long duration, TimeUnit unit) { return true; }
        };
        var callbackServer = (Mu3ServerImpl) MuServerBuilder.httpServer().withHandlerExecutor(callbackExecutor).start();
        try {
            for (String ending : List.of("success", "failure", "cancel", "rejected")) {
                var output = new AsyncResponseOutput(queuedIo::add, buffer -> { }, active -> { },
                    new SerialApplicationTasks(callbackServer));
                var payload = ByteBuffer.allocate(16384);
                var delivered = new java.util.concurrent.atomic.AtomicInteger();
                DoneCallback callback = error -> delivered.incrementAndGet();
                if (ending.equals("rejected")) output.complete(null);
                Future<?> future = output.write(payload, callback);
                if (!ending.equals("rejected")) {
                    assertTrue(retains(future, output), "Pending cancellation must still reach its output");
                    if (ending.equals("failure")) output.complete(new IOException("closed"));
                    else if (ending.equals("cancel")) assertTrue(future.cancel(false));
                    while (!queuedIo.isEmpty()) queuedIo.remove().run();
                }
                assertTrue(future.isDone());
                assertFalse(retains(future, payload), ending + ": completed future retains its payload");
                assertFalse(retains(future, callback), ending + ": completed future retains its callback");
                assertFalse(retains(future, output), ending + ": completed future retains its response");
                // An application callback may be delayed independently of successful I/O.
                assertEquals(0, delivered.get());
                for (Runnable task : queuedCallbacks) {
                    assertFalse(retains(task, payload), ending + ": callback dispatch retains an unused payload");
                }
                while (!queuedCallbacks.isEmpty()) queuedCallbacks.remove().run();
                assertEquals(1, delivered.get());
                assertFalse(future.cancel(false), "A finished future cannot cancel a later write");
            }
        } finally { callbackServer.stop(0, TimeUnit.SECONDS); }
    }

    /** Inspect Mu-owned strong references without relying on GC timing or opening JDK modules. */
    private static boolean retains(Object root, Object target) throws Exception {
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        var pending = new java.util.ArrayDeque<Object>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Object value = pending.remove();
            if (value == target) return true;
            if (!seen.add(value)) continue;
            if (value instanceof java.util.Collection<?>) {
                for (Object item : (java.util.Collection<?>) value) if (item != null) pending.add(item);
            } else {
                for (Class<?> type = value.getClass(); type != null && type.getName().startsWith("io.muserver."); type = type.getSuperclass()) {
                    for (var field : type.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                        field.setAccessible(true);
                        Object reference = field.get(value);
                        if (reference != null) pending.add(reference);
                    }
                }
            }
        }
        return false;
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
