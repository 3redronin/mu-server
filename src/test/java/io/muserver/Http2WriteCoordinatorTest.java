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
        var coordinator = coordinator(0, 1, 100);
        var blockedData = task(data(1, "not sent"));

        coordinator.submit(blockedData);
        coordinator.processAvailableCommands();
        assertThat(coordinator.pollWritable(), nullValue());

        var reset = task(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()));
        coordinator.submit(reset);
        coordinator.processAvailableCommands();

        var writableReset = coordinator.pollWritable();
        assertThat(writableReset.frame(), equalTo(reset.frame()));
        writableReset.complete();

        var credit = coordinator.applyConnectionWindowUpdate(100);
        coordinator.processAvailableCommands();
        credit.await();
        assertThat(coordinator.pollWritable(), nullValue());

        var lateData = task(data(1, "also not sent"));
        coordinator.submit(lateData);
        coordinator.processAvailableCommands();
        assertThrows(IOException.class, () -> lateData.await(1, TimeUnit.SECONDS));
        IOException failure = assertThrows(IOException.class, () -> blockedData.await(1, TimeUnit.SECONDS));
        assertThat(failure.getMessage(), containsString("locally reset"));
    }

    @Test
    void peerResetDiscardsPendingWritesAfterTheApplicationExchangeEndsAndRejectsLaterWrites() throws Exception {
        var coordinator = coordinator(0, 1, 100);
        var pending = task(data(1, "pending"));
        coordinator.submit(pending);
        coordinator.forgetStream(1);
        coordinator.processAvailableCommands();

        var reset = coordinator.resetStream(1, new IOException("peer reset stream 1"), null);
        coordinator.processAvailableCommands();
        reset.await();

        var late = task(data(1, "late"));
        coordinator.submit(late);
        coordinator.processAvailableCommands();

        IOException pendingFailure = assertThrows(IOException.class, () -> pending.await(1, TimeUnit.SECONDS));
        assertThat(pendingFailure.getMessage(), equalTo("peer reset stream 1"));
        IOException lateFailure = assertThrows(IOException.class, () -> late.await(1, TimeUnit.SECONDS));
        assertThat(lateFailure.getMessage(), containsString("was reset"));
        assertThat(coordinator.pollWritable(), nullValue());
    }

    @Test
    void blockedStreamDoesNotBlockOtherStreamsButRetainsItsOwnOrder() throws Exception {
        var coordinator = coordinator(0, 1, 100, 3, 100);
        var streamOneData = task(data(1, "first"));
        var streamOneHeaders = task(headers(1));
        var streamThreeHeaders = task(headers(3));
        coordinator.submit(streamOneData);
        coordinator.submit(streamOneHeaders);
        coordinator.submit(streamThreeHeaders);
        coordinator.processAvailableCommands();

        assertThat(
            coordinator.pollWritable().frame(),
            equalTo(streamThreeHeaders.frame())
        );

        var credit = coordinator.applyConnectionWindowUpdate(100);
        coordinator.processAvailableCommands();
        credit.await();
        assertThat(
            coordinator.pollWritable().frame(),
            equalTo(streamOneData.frame())
        );
        assertThat(
            coordinator.pollWritable().frame(),
            equalTo(streamOneHeaders.frame())
        );
    }

    @Test
    void resetOnlyDiscardsFramesForItsStream() {
        var coordinator = coordinator(100, 1, 100, 3, 100);
        var streamOneData = task(data(1, "one"));
        var streamThreeData = task(data(3, "three"));
        var reset = task(new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()));
        coordinator.submit(streamOneData);
        coordinator.submit(streamThreeData);
        coordinator.submit(reset);
        coordinator.processAvailableCommands();

        assertThat(
            coordinator.pollWritable().frame(),
            equalTo(streamThreeData.frame())
        );
        var next = coordinator.pollWritable();
        assertThat(next.frame(), equalTo(reset.frame()));
        assertThat(next.frame(), instanceOf(Http2ResetStreamFrame.class));
        assertThat(coordinator.isIdle(), is(true));
    }

    @Test
    void additionalResetsForLateFramesAreRetained() {
        var coordinator = new Http2WriteCoordinator(0);
        var first = task(new Http2ResetStreamFrame(1, Http2ErrorCode.STREAM_CLOSED.code()));
        var second = task(new Http2ResetStreamFrame(1, Http2ErrorCode.STREAM_CLOSED.code()));
        coordinator.submit(first);
        coordinator.submit(second);
        coordinator.processAvailableCommands();

        assertThat(
            coordinator.pollWritable().frame(),
            equalTo(first.frame())
        );
        assertThat(
            coordinator.pollWritable().frame(),
            equalTo(second.frame())
        );
    }

    @Test
    void dataIsFragmentedToAvailableCreditAndOnlyCompletesAfterTheLastFragment() throws Exception {
        var coordinator = coordinator(5, 1, 1);
        var data = task(new Http2DataFrame(1, true, "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), 0, 5));
        coordinator.submit(data);
        coordinator.processAvailableCommands();

        for (char expected : "hello".toCharArray()) {
            var writable = coordinator.pollWritable();
            assertThat(((Http2DataFrame) writable.frame()).toUTF8(), equalTo(String.valueOf(expected)));
            assertThat(writable.frame().endStream(), equalTo(expected == 'o'));
            writable.complete();
            if (expected != 'o') {
                var credit = coordinator.applyStreamWindowUpdate(1, 1);
                coordinator.processAvailableCommands();
                credit.await();
            }
        }

        data.await(1, TimeUnit.SECONDS);
        assertThat(coordinator.isIdle(), is(true));
    }

    @Test
    void settingsAckPrecedesDataUnblockedByTheSettingsChange() throws Exception {
        var coordinator = coordinator(100, 1, 0);
        var data = task(data(1, "newly writable"));
        coordinator.submit(data);
        coordinator.processAvailableCommands();

        assertThat(coordinator.pollWritable(), nullValue());

        var ack = task(Http2Settings.ACK);
        var settingsApplied = coordinator.applyInitialWindowSizeChange("newly writable".length(), ack);
        coordinator.processAvailableCommands();
        settingsApplied.await();

        assertThat(
            coordinator.pollWritable().frame(),
            equalTo(Http2Settings.ACK)
        );
        assertThat(
            coordinator.pollWritable().frame(),
            equalTo(data.frame())
        );
    }

    @Test
    void settingsChangeCanMakeCreditNegativeUntilWindowUpdatesArrive() throws Exception {
        var coordinator = coordinator(2_000, 1, 1_000);
        var first = task(data(1, repeat('a', 500)));
        coordinator.submit(first);
        coordinator.processAvailableCommands();
        coordinator.pollWritable().complete();

        var settingsApplied = coordinator.applyInitialWindowSizeChange(-800, task(Http2Settings.ACK));
        coordinator.processAvailableCommands();
        settingsApplied.await();
        assertThat(coordinator.pollWritable().frame(), equalTo(Http2Settings.ACK));

        var blocked = task(data(1, repeat('b', 100)));
        coordinator.submit(blocked);
        coordinator.processAvailableCommands();
        assertThat(coordinator.pollWritable(), nullValue());

        var firstUpdate = coordinator.applyStreamWindowUpdate(1, 200);
        coordinator.processAvailableCommands();
        firstUpdate.await();
        assertThat(coordinator.pollWritable(), nullValue());

        var secondUpdate = coordinator.applyStreamWindowUpdate(1, 200);
        coordinator.processAvailableCommands();
        secondUpdate.await();
        assertThat(coordinator.pollWritable().frame(), equalTo(blocked.frame()));
    }

    @Test
    void connectionWindowUpdateOverflowIsAConnectionFlowControlErrorAndStopsNormalWrites() {
        var coordinator = coordinator(Integer.MAX_VALUE - 1, 1, 100);
        coordinator.submit(task(data(1, "must not be sent")));
        var update = coordinator.applyConnectionWindowUpdate(2);
        coordinator.processAvailableCommands();

        Http2Exception failure = assertThrows(Http2Exception.class, update::await);
        assertThat(failure.errorType(), equalTo(Http2Level.CONNECTION));
        assertThat(failure.errorCode(), equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR));
        assertThat(failure.getMessage(), containsString("Credit overflow"));
        assertThat(coordinator.pollWritable(), nullValue());

        var goAway = task(new Http2GoAway(1, Http2ErrorCode.FLOW_CONTROL_ERROR.code(), null));
        coordinator.submit(goAway);
        coordinator.processAvailableCommands();
        assertThat(coordinator.pollWritable().frame(), equalTo(goAway.frame()));
        assertThat(coordinator.pollWritable(), nullValue());
    }

    @Test
    void streamWindowUpdateOverflowIsAStreamFlowControlErrorAndStopsThatStream() {
        var coordinator = coordinator(100, 1, Integer.MAX_VALUE - 1);
        var data = task(data(1, "must not be sent"));
        coordinator.submit(data);
        var update = coordinator.applyStreamWindowUpdate(1, 2);
        coordinator.processAvailableCommands();

        Http2Exception failure = assertThrows(Http2Exception.class, update::await);
        assertThat(failure.errorType(), equalTo(Http2Level.STREAM));
        assertThat(failure.errorCode(), equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR));
        assertThat(failure.getMessage(), containsString("Credit overflow"));
        assertThat(coordinator.pollWritable(), nullValue());

        var reset = task(new Http2ResetStreamFrame(1, Http2ErrorCode.FLOW_CONTROL_ERROR.code()));
        coordinator.submit(reset);
        coordinator.processAvailableCommands();
        assertThat(coordinator.pollWritable().frame(), equalTo(reset.frame()));
        assertThrows(IOException.class, () -> data.await(1, TimeUnit.SECONDS));
    }

    @Test
    void settingsWindowOverflowIsAConnectionFlowControlErrorAndIsNotAcknowledged() {
        var coordinator = coordinator(0, 1, Integer.MAX_VALUE - 1);
        var settingsApplied = coordinator.applyInitialWindowSizeChange(2, task(Http2Settings.ACK));
        coordinator.processAvailableCommands();

        Http2Exception failure = assertThrows(Http2Exception.class, settingsApplied::await);
        assertThat(failure.errorType(), equalTo(Http2Level.CONNECTION));
        assertThat(failure.errorCode(), equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR));
        assertThat(coordinator.pollWritable(), nullValue());
    }

    @Test
    void windowUpdatesForClosedStreamsAreIgnored() throws Exception {
        var coordinator = new Http2WriteCoordinator(0);
        var update = coordinator.applyStreamWindowUpdate(1, 1);
        coordinator.processAvailableCommands();
        update.await();

        assertThat(coordinator.isIdle(), is(true));
    }

    @Test
    void dataForAnUnopenedStreamCannotBypassStreamFlowControl() {
        var coordinator = new Http2WriteCoordinator(100);
        var data = task(data(1, "blocked"));
        coordinator.submit(data);
        coordinator.processAvailableCommands();

        assertThrows(IOException.class, () -> data.await(1, TimeUnit.SECONDS));
        assertThat(coordinator.pollWritable(), nullValue());
    }

    @Test
    void forgottenStreamRetainsCreditUntilItsPendingDataIsDrained() throws Exception {
        var coordinator = coordinator(100, 1, 0);
        var data = task(data(1, "later"));
        coordinator.submit(data);
        coordinator.forgetStream(1);
        coordinator.processAvailableCommands();
        assertThat(coordinator.pollWritable(), nullValue());

        var update = coordinator.applyStreamWindowUpdate(1, 5);
        coordinator.processAvailableCommands();
        update.await();

        assertThat(coordinator.pollWritable().frame(), equalTo(data.frame()));
        assertThat(coordinator.isIdle(), is(true));
    }

    private static Http2WriteCoordinator coordinator(int connectionCredit, int... streamIdsAndCredits) {
        var coordinator = new Http2WriteCoordinator(connectionCredit);
        for (int i = 0; i < streamIdsAndCredits.length; i += 2) {
            coordinator.openStream(streamIdsAndCredits[i], streamIdsAndCredits[i + 1]);
        }
        coordinator.processAvailableCommands();
        return coordinator;
    }

    private static String repeat(char value, int length) {
        StringBuilder result = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            result.append(value);
        }
        return result.toString();
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
