package io.muserver;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Http2CancellationRecoveryTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancellingBlockedWritesReleasesWaitersAndPreservesOtherStreamCredit(boolean applicationEndsFirst) throws Exception {
        Http2WriteCoordinator coordinator = new Http2WriteCoordinator(8);
        for (int cycle = 0; cycle < 3; cycle++) {
            int cancelled = cycle * 4 + 1;
            int healthy = cancelled + 2;
            coordinator.openStream(cancelled, 0);
            coordinator.openStream(healthy, 8);
            WriteTask blocked = data(cancelled, "blocked", false);
            WriteTask secondBlocked = data(cancelled, "also blocked", true);
            WriteTask survivor = data(healthy, "survives", true);
            coordinator.submit(blocked);
            coordinator.submit(secondBlocked);
            coordinator.processAvailableCommands();
            assertNull(coordinator.pollWritable(), "Cancelled stream must first be blocked on credit");
            coordinator.submit(survivor);
            if (applicationEndsFirst) coordinator.applicationExchangeEnded(cancelled);
            IOException reason = new IOException("peer cancelled " + cancelled);
            coordinator.resetStream(new Http2ResetStreamFrame(cancelled, Http2ErrorCode.CANCEL.code()), reason, null);
            coordinator.processAvailableCommands();
            assertSame(reason, assertThrows(IOException.class, () -> blocked.await(1, TimeUnit.SECONDS)));
            assertSame(reason, assertThrows(IOException.class, () -> secondBlocked.await(1, TimeUnit.SECONDS)));

            if (!applicationEndsFirst) coordinator.applicationExchangeEnded(cancelled);
            coordinator.processAvailableCommands();
            assertNull(coordinator.streamState(cancelled));
            assertEquals(0, coordinator.resetRecordCount());
            // Late credit and a duplicate reset must not revive the cancelled data.
            coordinator.applyStreamWindowUpdate(cancelled, 100);
            coordinator.resetStream(new Http2ResetStreamFrame(cancelled, Http2ErrorCode.CANCEL.code()), reason, null);
            coordinator.processAvailableCommands();
            Http2WriteCoordinator.WritableFrame writable = coordinator.pollWritable();
            assertNotNull(writable);
            assertEquals(healthy, writable.frame().streamId());
            assertEquals("survives", ((Http2DataFrame) writable.frame()).toUTF8());
            assertTrue(writable.beginWrite());
            writable.complete();
            survivor.await(1, TimeUnit.SECONDS);
            assertNull(coordinator.pollWritable());
            assertTrue(coordinator.isIdle(), "No pending writes or commands may retain cancelled work");
            assertEquals(0, coordinator.resetRecordCount());
            coordinator.applicationExchangeEnded(healthy);
            coordinator.processAvailableCommands();
            assertNull(coordinator.streamState(healthy));
            if (cycle < 2) {
                coordinator.applyConnectionWindowUpdate(8, healthy);
                coordinator.processAvailableCommands();
            }
        }
    }

    private static WriteTask data(int stream, String text, boolean endStream) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        return new WriteTask(new Http2DataFrame(stream, endStream, bytes, 0, bytes.length), true);
    }
}
