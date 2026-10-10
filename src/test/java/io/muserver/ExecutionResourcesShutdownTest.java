package io.muserver;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class ExecutionResourcesShutdownTest {
    @Test
    void stoppingTheServerAllowsLateIoCallbacksWithoutWaitingForTheirWorker() throws Exception {
        var application = Executors.newSingleThreadExecutor();
        var internal = Executors.newSingleThreadExecutor();
        var timer = Executors.newSingleThreadScheduledExecutor();
        var resources = new ExecutionResources(application, true, internal, timer);
        var releaseIo = new CountDownLatch(1);
        var ioStarted = new CountDownLatch(1);
        var delivered = new CompletableFuture<Void>();
        var builder = MuServerBuilder.httpServer();
        builder.executionResourcesFactory = supplied -> resources;
        try (var server = builder.start()) {
            Future<?> io = internal.submit(() -> {
                ioStarted.countDown();
                try { assertTrue(releaseIo.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                application.execute(() -> delivered.complete(null));
            });
            assertTrue(ioStarted.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5),
                () -> server.stop(0, TimeUnit.SECONDS));
            assertTrue(timer.isShutdown());
            assertTrue(internal.isShutdown());
            assertFalse(application.isShutdown(), "Final I/O callbacks must still be accepted");
            releaseIo.countDown();
            io.get(5, TimeUnit.SECONDS);
            delivered.get(5, TimeUnit.SECONDS);
            assertTrue(internal.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(application.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(timer.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            releaseIo.countDown();
            internal.shutdownNow();
            application.shutdownNow();
            timer.shutdownNow();
        }
    }

    @Test
    void finalIoCallbacksAreAcceptedBeforeOwnedApplicationExecutorShutsDown() throws Exception {
        var application = Executors.newSingleThreadExecutor(r -> new Thread(r, "shutdown-test-application"));
        var internal = Executors.newSingleThreadExecutor();
        var timer = Executors.newSingleThreadScheduledExecutor();
        var resources = new ExecutionResources(application, true, internal, timer);
        var releaseIo = new CountDownLatch(1);
        var ioStarted = new CountDownLatch(1);
        var delivered = new CompletableFuture<String>();
        try {
            Future<?> io = internal.submit(() -> {
                ioStarted.countDown();
                try { assertTrue(releaseIo.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                application.execute(() -> delivered.complete(Thread.currentThread().getName()));
            });
            assertTrue(ioStarted.await(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), resources::shutdown);
            resources.shutdown(); // Repeated shutdown must not close the callback executor early.
            assertTrue(internal.isShutdown());
            assertTrue(timer.isShutdown());
            assertFalse(application.isShutdown());
            releaseIo.countDown();
            io.get(5, TimeUnit.SECONDS);
            assertEquals("shutdown-test-application", delivered.get(5, TimeUnit.SECONDS));
            assertTrue(application.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(internal.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            releaseIo.countDown();
            internal.shutdownNow();
            application.shutdownNow();
            timer.shutdownNow();
        }
    }
}
