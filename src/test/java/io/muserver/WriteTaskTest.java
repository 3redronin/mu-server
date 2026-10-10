package io.muserver;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WriteTaskTest {
    private static WriteTask task() {
        return new WriteTask(new Http2DataFrame(1, false, new byte[]{1, 2}, 0, 2), true);
    }

    @Test void completionCanBeRegisteredBeforeOrAfterTheWriterFinishes() {
        for (boolean early : new boolean[]{false, true}) {
            WriteTask task = task();
            CompletableFuture<?> completed = early ? task.completion() : null;
            assertTrue(task.beginWrite());
            task.complete();
            if (!early) completed = task.completion();
            assertNull(completed.join());
            assertSame(completed, task.completion());
            assertFalse(task.beginWrite());
        }
    }

    @Test void partialCreditDoesNotCompleteTheLogicalWrite() {
        WriteTask task = task();
        var completed = task.completion();
        assertTrue(task.beginWrite());
        task.finishPart(false);
        assertFalse(completed.isDone());
        assertTrue(task.beginWrite());
        task.finishPart(true);
        assertNull(completed.join());
    }

    @Test void rejectingAnUnsentRemainderWaitsForTheActiveFragment() throws Exception {
        WriteTask task = task();
        var completed = task.completion();
        assertTrue(task.beginWrite());
        var reset = new IOException("peer reset");
        task.fail(reset);
        assertFalse(completed.isDone());
        assertFalse(task.beginWrite());
        task.finishPart(false);
        assertSame(reset, assertThrows(CompletionException.class, completed::join).getCause());
        assertSame(reset, assertThrows(IOException.class, task::await));
    }

    @Test void writeFailureAcknowledgesOwnershipAndPreservesTheFirstCause() {
        WriteTask task = task();
        var cancelled = new IOException("cancelled");
        assertTrue(task.beginWrite());
        assertTrue(task.cancel(cancelled));
        assertFalse(task.completion().isDone());
        task.writeFailed(new IOException("transport closed"));
        assertSame(cancelled, assertThrows(CompletionException.class, task.completion()::join).getCause());
        task.fail(new IOException("later failure"));
        assertSame(cancelled, assertThrows(CompletionException.class, task.completion()::join).getCause());
    }

    @Test void completionListenersRunOutsideTheTaskMonitor() {
        for (int outcome = 0; outcome < 4; outcome++) {
            WriteTask task = task();
            var checked = task.completion().handle((ignored, failure) -> {
                assertFalse(Thread.holdsLock(task));
                return null;
            });
            if (outcome == 0) task.complete();
            else if (outcome == 1) task.cancel(new IOException("cancel"));
            else if (outcome == 2) task.fail(new IOException("fail"));
            else { assertTrue(task.beginWrite()); task.writeFailed(new IOException("write failed")); }
            checked.join();
        }
    }

    @Test void failedCompletionRegisteredLateRetainsItsCause() {
        WriteTask task = task();
        IOException failure = new IOException("failed before registration");
        task.fail(failure);
        assertSame(failure, assertThrows(CompletionException.class, task.completion()::join).getCause());
    }

    @Test void aWaitTimeoutDoesNotChangeTheWriterOutcome() throws Exception {
        WriteTask task = task();
        assertThrows(IOException.class, () -> task.await(1, TimeUnit.MILLISECONDS));
        assertFalse(task.completion().isDone());
        assertTrue(task.beginWrite());
        task.complete();
        task.await();
        assertNull(task.completion().join());
    }

    @Test void cancelledQueuedDataCannotStartWriting() throws Exception {
        WriteTask task = new WriteTask(new Http2DataFrame(1, false, new byte[]{1}, 0, 1), true);
        IOException cancelled = new IOException("cancelled");
        assertFalse(task.cancel(cancelled));
        assertFalse(task.beginWrite());
        assertSame(cancelled, assertThrows(IOException.class, task::await));
    }

    @Test void activeDataKeepsOwnershipUntilTheWriterTerminates() throws Exception {
        WriteTask task = new WriteTask(new Http2DataFrame(1, false, new byte[]{1}, 0, 1), true);
        assertTrue(task.beginWrite());
        IOException cancelled = new IOException("cancelled");
        assertTrue(task.cancel(cancelled));
        var waiter = Executors.newSingleThreadExecutor();
        Future<?> completion = waiter.submit(() -> { task.await(); return null; });
        try {
            assertThrows(TimeoutException.class, () -> completion.get(50, TimeUnit.MILLISECONDS));
            task.finishPart(false);
            assertSame(cancelled, assertThrows(ExecutionException.class, () -> completion.get(5, TimeUnit.SECONDS)).getCause());
            assertFalse(task.beginWrite());
        } finally { task.fail(cancelled); waiter.shutdownNow(); }
    }
}
