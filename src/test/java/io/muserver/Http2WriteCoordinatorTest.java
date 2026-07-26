package io.muserver;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2WriteCoordinatorTest {

    @Test
    void resetDiscardsBlockedDataAndLaterCreditCannotMakeItWritable() throws Exception {
        var coordinator = new Http2WriteCoordinator();
        var blockedData = task(data(1, "not sent"));

        coordinator.submit(blockedData);
        coordinator.processAvailableCommands();
        assertThat(coordinator.pollWritable((streamId, requested) -> 0), nullValue());

        var reset = task(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()));
        coordinator.submit(reset);
        coordinator.processAvailableCommands();

        var writableReset = coordinator.pollWritable((streamId, requested) -> requested);
        assertThat(writableReset.frame(), equalTo(reset.frame()));
        writableReset.complete();

        // This reservation represents connection and stream credit arriving after the reset.
        assertThat(coordinator.pollWritable((streamId, requested) -> requested), nullValue());
        var lateData = task(data(1, "also not sent"));
        coordinator.submit(lateData);
        coordinator.processAvailableCommands();
        assertThrows(IOException.class, () -> lateData.await(1, TimeUnit.SECONDS));
        IOException failure = assertThrows(IOException.class, () -> blockedData.await(1, TimeUnit.SECONDS));
        assertThat(failure.getMessage(), containsString("locally reset"));
    }

    @Test
    void peerResetDiscardsPendingWritesAndRejectsLaterWrites() {
        var coordinator = new Http2WriteCoordinator();
        var pending = task(data(1, "pending"));
        coordinator.submit(pending);
        coordinator.processAvailableCommands();

        coordinator.resetStream(1, new IOException("peer reset stream 1"));
        coordinator.processAvailableCommands();

        var late = task(data(1, "late"));
        coordinator.submit(late);
        coordinator.processAvailableCommands();

        IOException pendingFailure = assertThrows(IOException.class, () -> pending.await(1, TimeUnit.SECONDS));
        assertThat(pendingFailure.getMessage(), equalTo("peer reset stream 1"));
        IOException lateFailure = assertThrows(IOException.class, () -> late.await(1, TimeUnit.SECONDS));
        assertThat(lateFailure.getMessage(), containsString("was reset"));
        assertThat(coordinator.pollWritable((streamId, requested) -> requested), nullValue());
    }

    @Test
    void blockedStreamDoesNotBlockOtherStreamsButRetainsItsOwnOrder() {
        var coordinator = new Http2WriteCoordinator();
        var streamOneData = task(data(1, "first"));
        var streamOneHeaders = task(headers(1));
        var streamThreeHeaders = task(headers(3));
        coordinator.submit(streamOneData);
        coordinator.submit(streamOneHeaders);
        coordinator.submit(streamThreeHeaders);
        coordinator.processAvailableCommands();

        assertThat(
            coordinator.pollWritable((streamId, requested) -> 0).frame(),
            equalTo(streamThreeHeaders.frame())
        );
        assertThat(
            coordinator.pollWritable((streamId, requested) -> requested).frame(),
            equalTo(streamOneData.frame())
        );
        assertThat(
            coordinator.pollWritable((streamId, requested) -> requested).frame(),
            equalTo(streamOneHeaders.frame())
        );
    }

    @Test
    void resetOnlyDiscardsFramesForItsStream() {
        var coordinator = new Http2WriteCoordinator();
        var streamOneData = task(data(1, "one"));
        var streamThreeData = task(data(3, "three"));
        var reset = task(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()));
        coordinator.submit(streamOneData);
        coordinator.submit(streamThreeData);
        coordinator.submit(reset);
        coordinator.processAvailableCommands();

        assertThat(
            coordinator.pollWritable((streamId, requested) -> requested).frame(),
            equalTo(streamThreeData.frame())
        );
        var next = coordinator.pollWritable((streamId, requested) -> requested);
        assertThat(next.frame(), equalTo(reset.frame()));
        assertThat(next.frame(), instanceOf(Http2ResetStreamFrame.class));
        assertThat(coordinator.isIdle(), is(true));
    }

    @Test
    void additionalResetsForLateFramesAreRetained() {
        var coordinator = new Http2WriteCoordinator();
        var first = task(new Http2ResetStreamFrame(1, Http2ErrorCode.STREAM_CLOSED.code()));
        var second = task(new Http2ResetStreamFrame(1, Http2ErrorCode.STREAM_CLOSED.code()));
        coordinator.submit(first);
        coordinator.submit(second);
        coordinator.processAvailableCommands();

        assertThat(
            coordinator.pollWritable((streamId, requested) -> requested).frame(),
            equalTo(first.frame())
        );
        assertThat(
            coordinator.pollWritable((streamId, requested) -> requested).frame(),
            equalTo(second.frame())
        );
    }

    @Test
    void dataIsFragmentedToAvailableCreditAndOnlyCompletesAfterTheLastFragment() throws Exception {
        var coordinator = new Http2WriteCoordinator();
        var data = task(new Http2DataFrame(1, true, "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, 5));
        coordinator.submit(data);
        coordinator.processAvailableCommands();

        for (char expected : "hello".toCharArray()) {
            var writable = coordinator.pollWritable((streamId, requested) -> Math.min(1, requested));
            assertThat(((Http2DataFrame) writable.frame()).toUTF8(), equalTo(String.valueOf(expected)));
            assertThat(writable.frame().endStream(), equalTo(expected == 'o'));
            writable.complete();
        }

        data.await(1, TimeUnit.SECONDS);
        assertThat(coordinator.isIdle(), is(true));
    }

    @Test
    void settingsAckPrecedesDataUnblockedByTheSettingsChange() {
        var coordinator = new Http2WriteCoordinator();
        var data = task(data(1, "newly writable"));
        coordinator.submit(data);
        coordinator.suspendDataScheduling();
        coordinator.processAvailableCommands();

        assertThat(
            coordinator.pollWritable((streamId, requested) -> requested),
            nullValue()
        );

        var ack = task(Http2Settings.ACK);
        coordinator.submitFirstAndResumeData(ack);
        coordinator.processAvailableCommands();

        assertThat(
            coordinator.pollWritable((streamId, requested) -> requested).frame(),
            equalTo(Http2Settings.ACK)
        );
        assertThat(
            coordinator.pollWritable((streamId, requested) -> requested).frame(),
            equalTo(data.frame())
        );
    }

    private static WriteTask task(LogicalHttp2Frame frame) {
        return new WriteTask(frame, true);
    }

    private static Http2DataFrame data(int streamId, String value) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new Http2DataFrame(streamId, false, bytes, 0, bytes.length);
    }

    private static Http2HeadersFrame headers(int streamId) {
        return new Http2HeadersFrame(streamId, false, new FieldBlock());
    }
}
