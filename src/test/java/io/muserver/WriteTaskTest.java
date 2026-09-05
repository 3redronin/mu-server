package io.muserver;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WriteTaskTest {
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
