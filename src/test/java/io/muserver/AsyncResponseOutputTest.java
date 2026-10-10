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

    @Test void aWriteCallbackSharesAnEarlierBodyCallbacksPendingDispatchOutcome() throws Exception {
        var dispatchEntered = new CountDownLatch(1);
        var rejectDispatch = new CountDownLatch(1);
        var rejecting = new AbstractExecutorService() {
            public void execute(Runnable task) {
                dispatchEntered.countDown();
                try { rejectDispatch.await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                throw new RejectedExecutionException("Controlled application rejection");
            }
            public void shutdown() { }
            public List<Runnable> shutdownNow() { return List.of(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
        var rejectingServer = (Mu3ServerImpl) MuServerBuilder.httpServer().withHandlerExecutor(rejecting).start();
        try {
            var callbacks = new SerialApplicationTasks(rejectingServer);
            var firstSubmission = io.submit(() -> callbacks.submit(() -> fail("Rejected body callback"), error -> {}));
            assertTrue(dispatchEntered.await(3, TimeUnit.SECONDS));
            var tasks = new java.util.ArrayDeque<Runnable>();
            var output = AsyncResponseOutput.asynchronous(tasks::add, data -> CompletableFuture.completedFuture(null), active -> {}, callbacks);
            output.write(ByteBuffer.allocate(1), error -> fail("Rejected write callback"));
            output.complete(null);
            tasks.remove().run();
            assertFalse(output.completion().isDone());
            rejectDispatch.countDown();
            firstSubmission.get(3, TimeUnit.SECONDS);
            assertInstanceOf(RejectedExecutionException.class,
                assertThrows(ExecutionException.class, () -> output.completion().get(3, TimeUnit.SECONDS)).getCause());
        } finally { rejectDispatch.countDown(); rejectingServer.stop(0, TimeUnit.MILLISECONDS); }
    }

    @Test void completionWaitsForCallbackDispatchAcceptanceWithoutWaitingForCallbackExecution() throws Exception {
        var tasks = new java.util.ArrayDeque<Runnable>();
        var output = AsyncResponseOutput.asynchronous(tasks::add, data -> CompletableFuture.completedFuture(null),
            active -> {}, new SerialApplicationTasks(server));
        var write = output.write(ByteBuffer.allocate(1), error -> fail("Application executor should reject this callback"));
        output.complete(null);
        tasks.remove().run();
        assertTrue(write.isDone());
        assertFalse(output.completion().isDone(), "Dispatch rejection must still be able to fail this exchange");
        application.shutdown();
        assertTrue(application.awaitTermination(3, TimeUnit.SECONDS));
        while (!tasks.isEmpty()) tasks.remove().run();
        assertInstanceOf(RejectedExecutionException.class,
            assertThrows(ExecutionException.class, () -> output.completion().get()).getCause());
    }

    @Test void aQueuedDrainCannotRetireTheExchangeBeforeCancelledFuturesAreSettled() throws Exception {
        var tasks = new LinkedBlockingQueue<Runnable>();
        var submissions = new java.util.concurrent.atomic.AtomicInteger();
        var dispatchEntered = new CountDownLatch(1);
        var releaseDispatch = new CountDownLatch(1);
        Executor executor = task -> {
            if (submissions.incrementAndGet() == 2) {
                dispatchEntered.countDown();
                try { releaseDispatch.await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            }
            tasks.add(task);
        };
        var output = AsyncResponseOutput.asynchronous(executor, data -> {
            throw new AssertionError("Output cancelled before starting");
        }, active -> {}, new SerialApplicationTasks(server));
        var first = output.write(ByteBuffer.allocate(1), error -> {});
        var second = output.write(ByteBuffer.allocate(1), null);
        var cancellation = io.submit(() -> output.complete(new IOException("Stop")));
        try {
            assertTrue(dispatchEntered.await(3, TimeUnit.SECONDS));
            assertTrue(first.isDone());
            assertFalse(second.isDone());
            tasks.remove().run();
            assertFalse(output.completion().isDone(), "A removed queue entry still owns its completion future");
            releaseDispatch.countDown();
            cancellation.get(3, TimeUnit.SECONDS);
            assertTrue(second.isDone());
            while (!tasks.isEmpty()) tasks.remove().run();
            assertTrue(output.completion().isDone());
        } finally { releaseDispatch.countDown(); }
    }

    @Test void anExceptionalWriteFutureRetainsItsCauseAndPreventsLaterIo() throws Exception {
        var started = new CountDownLatch(1);
        var pending = new CompletableFuture<Void>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var aborts = new java.util.concurrent.atomic.AtomicInteger();
        var output = AsyncResponseOutput.asynchronous(io, data -> {
            calls.incrementAndGet(); started.countDown(); return pending;
        }, active -> aborts.incrementAndGet(), new SerialApplicationTasks(server));
        var first = output.write(ByteBuffer.allocate(1), null);
        var second = output.write(ByteBuffer.allocate(1), null);
        output.complete(null);
        assertTrue(started.await(3, TimeUnit.SECONDS));
        IOException failure = new IOException("TLS drain failed");
        pending.completeExceptionally(new CompletionException(failure));
        for (Future<?> result : List.of(first, second, output.completion())) {
            assertSame(failure, assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS)).getCause());
        }
        assertEquals(1, calls.get());
        assertEquals(1, aborts.get());
    }

    @Test void immediatelyCompletedWritesYieldAfterABoundedTurn() throws Exception {
        var tasks = new java.util.ArrayDeque<Runnable>();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var output = AsyncResponseOutput.asynchronous(tasks::add, data -> {
            calls.incrementAndGet(); return CompletableFuture.completedFuture(null);
        }, active -> {}, new SerialApplicationTasks(server));
        for (int i = 0; i < 1000; i++) output.write(ByteBuffer.allocate(1), null);
        output.complete(null);
        assertEquals(1, tasks.size());
        tasks.remove().run();
        assertEquals(64, calls.get());
        assertFalse(output.completion().isDone());
        while (!tasks.isEmpty()) tasks.remove().run();
        output.completion().get(3, TimeUnit.SECONDS);
        assertEquals(1000, calls.get());
    }

    @Test void failedTransportAbortStillSettlesQueuedWritesAndRetainsItsDiagnostic() throws Exception {
        var tasks = new java.util.ArrayDeque<Runnable>();
        var abortFailure = new IllegalStateException("Abort failed");
        var output = AsyncResponseOutput.asynchronous(tasks::add, data -> {
            throw new AssertionError("Cancelled before dispatch");
        }, active -> { throw abortFailure; }, new SerialApplicationTasks(server));
        var write = output.write(ByteBuffer.allocate(1), null);
        var failure = new IOException("Stop");
        output.complete(failure);
        assertSame(failure, assertThrows(ExecutionException.class, write::get).getCause());
        assertSame(failure, assertThrows(ExecutionException.class, () -> output.completion().get()).getCause());
        assertArrayEquals(new Throwable[]{abortFailure}, failure.getSuppressed());
        while (!tasks.isEmpty()) tasks.remove().run();
    }

    @Test void asynchronousWritesSuspendTheWorkerAndResumeInOrderAfterDrain() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var writes = new LinkedBlockingQueue<CompletableFuture<Void>>();
        var bytes = new CopyOnWriteArrayList<Integer>();
        var delivered = new CopyOnWriteArrayList<Integer>();
        try {
            var output = AsyncResponseOutput.asynchronous(worker, data -> {
                bytes.add((int) data.get(0));
                var completion = new CompletableFuture<Void>();
                writes.add(completion);
                return completion;
            }, active -> {}, new SerialApplicationTasks(server));
            Future<?> first = output.write(ByteBuffer.wrap(new byte[]{1}), error -> delivered.add(1));
            Future<?> second = output.write(ByteBuffer.wrap(new byte[]{2}), error -> delivered.add(2));
            output.complete(null);
            var firstIo = writes.poll(3, TimeUnit.SECONDS);
            assertNotNull(firstIo);
            assertEquals(42, worker.submit(() -> 42).get(3, TimeUnit.SECONDS));
            assertEquals(List.of(1), bytes);
            assertFalse(first.isDone());
            assertFalse(output.completion().isDone());
            firstIo.complete(null);
            first.get(3, TimeUnit.SECONDS);
            var secondIo = writes.poll(3, TimeUnit.SECONDS);
            assertNotNull(secondIo);
            assertEquals(43, worker.submit(() -> 43).get(3, TimeUnit.SECONDS));
            assertFalse(second.isDone());
            secondIo.complete(null);
            output.completion().get(3, TimeUnit.SECONDS);
            worker.submit(() -> {}).get(3, TimeUnit.SECONDS);
            application.submit(() -> {}).get(3, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2), bytes);
            assertEquals(List.of(1, 2), delivered);
        } finally { worker.shutdownNow(); }
    }

    @Test void asynchronousCancellationRetainsTheSourceUntilItsWriteAcknowledgesAbort() throws Exception {
        var pending = new CompletableFuture<Void>();
        var started = new CountDownLatch(1);
        var aborted = new CountDownLatch(1);
        var output = AsyncResponseOutput.asynchronous(io, data -> {
            started.countDown(); return pending;
        }, active -> { assertTrue(active); aborted.countDown(); }, new SerialApplicationTasks(server));
        Future<?> write = output.write(ByteBuffer.allocate(16), null);
        assertTrue(started.await(3, TimeUnit.SECONDS));
        Future<Boolean> cancellation = io.submit(() -> write.cancel(false));
        assertTrue(aborted.await(3, TimeUnit.SECONDS));
        assertFalse(write.isDone());
        assertFalse(cancellation.isDone());
        assertFalse(pending.isCancelled(), "The transport acknowledgement is not a cancellation API");
        pending.complete(null);
        assertTrue(cancellation.get(3, TimeUnit.SECONDS));
        assertThrows(CancellationException.class, write::get);
        assertThrows(CancellationException.class, () -> output.completion().get(3, TimeUnit.SECONDS));
    }

    @Test void aCallerRunsWriteCallbackCanWaitForAnotherWriteWithoutRetainingTheDrain() throws Exception {
        var direct = new AbstractExecutorService() {
            public void execute(Runnable task) { task.run(); }
            public void shutdown() { }
            public List<Runnable> shutdownNow() { return List.of(); }
            public boolean isShutdown() { return false; }
            public boolean isTerminated() { return false; }
            public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        };
        var inlineServer = (Mu3ServerImpl) MuServerBuilder.httpServer().withHandlerExecutor(direct).start();
        var outputRef = new java.util.concurrent.atomic.AtomicReference<AsyncResponseOutput>();
        var bytes = new CopyOnWriteArrayList<Integer>();
        var callback = new CompletableFuture<Void>();
        try {
            var output = AsyncResponseOutput.asynchronous(io, data -> {
                bytes.add((int) data.get(0)); return CompletableFuture.completedFuture(null);
            }, active -> {}, new SerialApplicationTasks(inlineServer));
            outputRef.set(output);
            output.write(ByteBuffer.wrap(new byte[]{1}), error -> {
                try {
                    assertNull(error);
                    outputRef.get().write(ByteBuffer.wrap(new byte[]{2}), null).get(3, TimeUnit.SECONDS);
                    outputRef.get().complete(null);
                    callback.complete(null);
                } catch (Throwable failure) { callback.completeExceptionally(failure); }
            });
            callback.get(5, TimeUnit.SECONDS);
            output.completion().get(3, TimeUnit.SECONDS);
            assertEquals(List.of(1, 2), bytes);
        } finally { inlineServer.stop(0, TimeUnit.MILLISECONDS); }
    }

    @Test void rejectedContinuationReleasesAnAcknowledgedWriteAndFailsItsQueuedWrites() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var pending = new CompletableFuture<Void>();
        var started = new CountDownLatch(1);
        var callbacksDone = new CountDownLatch(2);
        var delivered = new CopyOnWriteArrayList<Integer>();
        var output = AsyncResponseOutput.asynchronous(worker, data -> { started.countDown(); return pending; },
            active -> {}, new SerialApplicationTasks(server));
        try {
            var first = output.write(ByteBuffer.allocate(1), error -> { delivered.add(1); callbacksDone.countDown(); });
            var second = output.write(ByteBuffer.allocate(1), error -> { delivered.add(2); callbacksDone.countDown(); });
            output.complete(null);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            worker.shutdown();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            pending.complete(null); // Simulate a selector notification after internal executor shutdown.
            for (Future<?> result : List.of(first, second, output.completion())) {
                assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS)).getCause());
            }
            assertTrue(callbacksDone.await(3, TimeUnit.SECONDS));
            assertEquals(List.of(1, 2), delivered);
        } finally { worker.shutdownNow(); }
    }

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
