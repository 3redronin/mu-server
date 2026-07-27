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

        coordinator.applyConnectionWindowUpdate(100, 1);
        coordinator.processAvailableCommands();
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

        coordinator.resetStream(
            new Http2ResetStreamFrame(1, Http2ErrorCode.CANCEL.code()),
            new IOException("peer reset stream 1"),
            null
        );
        coordinator.processAvailableCommands();

        var late = task(data(1, "late"));
        coordinator.submit(late);
        coordinator.processAvailableCommands();

        IOException pendingFailure = assertThrows(IOException.class, () -> pending.await(1, TimeUnit.SECONDS));
        assertThat(pendingFailure.getMessage(), equalTo("peer reset stream 1"));
        IOException lateFailure = assertThrows(IOException.class, () -> late.await(1, TimeUnit.SECONDS));
        assertThat(lateFailure.getMessage(), containsString("was not open"));
        assertThat(coordinator.pollWritable(), nullValue());
        assertThat(coordinator.streamState(1), nullValue());
    }

    @Test
    void remoteAndLocalEndStreamTransitionsAreSerializedInEitherOrder() {
        var remoteFirst = coordinator(100, 1, 100);
        remoteFirst.remoteEndStream(1);
        remoteFirst.submit(task(new Http2HeadersFrame(1, true, new FieldBlock())));
        remoteFirst.processAvailableCommands();
        assertThat(remoteFirst.streamState(1), equalTo(Http2StreamState.CLOSED));

        var localFirst = coordinator(100, 1, 100);
        localFirst.submit(task(new Http2HeadersFrame(1, true, new FieldBlock())));
        localFirst.remoteEndStream(1);
        localFirst.processAvailableCommands();
        assertThat(localFirst.streamState(1), equalTo(Http2StreamState.CLOSED));
    }

    @Test
    void remoteEndStreamLeavesTheResponseSideWritableUntilItEnds() {
        var coordinator = coordinator(100, 1, 100);
        var headers = task(headers(1));
        var end = task(new Http2HeadersFrame(1, true, new FieldBlock()));
        coordinator.remoteEndStream(1);
        coordinator.submit(headers);
        coordinator.submit(end);
        coordinator.processAvailableCommands();

        assertThat(coordinator.pollWritable().frame(), equalTo(headers.frame()));
        assertThat(coordinator.pollWritable().frame(), equalTo(end.frame()));
        assertThat(coordinator.streamState(1), equalTo(Http2StreamState.CLOSED));
    }

    @Test
    void outboundFramesAfterLocalEndStreamAreRejected() throws Exception {
        var coordinator = coordinator(100, 1, 100);
        var end = task(new Http2HeadersFrame(1, true, new FieldBlock()));
        var late = task(headers(1));
        coordinator.submit(end);
        coordinator.submit(late);
        coordinator.processAvailableCommands();

        assertThat(coordinator.pollWritable().frame(), equalTo(end.frame()));
        IOException failure = assertThrows(IOException.class, () -> late.await(1, TimeUnit.SECONDS));
        assertThat(failure.getMessage(), containsString("was not open"));
        assertThat(coordinator.streamState(1), equalTo(Http2StreamState.HALF_CLOSED_LOCAL));
    }

    @Test
    void connectionFailureMakesGoAwayTheOnlyWritableFrame() throws Exception {
        var coordinator = coordinator(100, 1, 100);
        var pending = task(headers(1));
        var goAway = task(new Http2GoAway(1, Http2ErrorCode.PROTOCOL_ERROR.code(), null));
        var lateStream = task(headers(1));
        var lateConnection = task(new Http2Ping(false, new byte[8]));
        coordinator.submit(pending);
        coordinator.failConnection(goAway, new IOException("connection failed"));
        coordinator.submit(lateStream);
        coordinator.submit(lateConnection);
        coordinator.processAvailableCommands();

        assertThat(coordinator.pollWritable().frame(), equalTo(goAway.frame()));
        assertThat(coordinator.pollWritable(), nullValue());
        assertThrows(IOException.class, () -> pending.await(1, TimeUnit.SECONDS));
        assertThrows(IOException.class, () -> lateStream.await(1, TimeUnit.SECONDS));
        assertThrows(IOException.class, () -> lateConnection.await(1, TimeUnit.SECONDS));
        assertThat(coordinator.streamState(1), equalTo(Http2StreamState.CLOSED));
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

        coordinator.applyConnectionWindowUpdate(100, 3);
        coordinator.processAvailableCommands();
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
    void streamWindowUpdateBypassesBlockedDataWithoutReorderingApplicationFrames() {
        var coordinator = coordinator(100, 1, 0);
        var blockedData = task(data(1, "first"));
        var laterHeaders = task(headers(1));
        var requestBodyCredit = task(new Http2WindowUpdate(1, 100));
        coordinator.submit(blockedData);
        coordinator.submit(laterHeaders);
        coordinator.submit(requestBodyCredit);
        coordinator.processAvailableCommands();

        var writableCredit = coordinator.pollWritable();
        assertThat(writableCredit.frame(), equalTo(requestBodyCredit.frame()));
        writableCredit.complete();
        assertThat(coordinator.pollWritable(), nullValue());

        coordinator.applyStreamWindowUpdate(1, 100);
        coordinator.processAvailableCommands();
        assertThat(coordinator.pollWritable().frame(), equalTo(blockedData.frame()));
        assertThat(coordinator.pollWritable().frame(), equalTo(laterHeaders.frame()));
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
        assertThat(coordinator.streamState(1), equalTo(Http2StreamState.CLOSED));
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
                coordinator.applyStreamWindowUpdate(1, 1);
                coordinator.processAvailableCommands();
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
        coordinator.applyInitialWindowSizeChange("newly writable".length(), ack, 1);
        coordinator.processAvailableCommands();

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

        coordinator.applyInitialWindowSizeChange(-800, task(Http2Settings.ACK), 1);
        coordinator.processAvailableCommands();
        assertThat(coordinator.pollWritable().frame(), equalTo(Http2Settings.ACK));

        var blocked = task(data(1, repeat('b', 100)));
        coordinator.submit(blocked);
        coordinator.processAvailableCommands();
        assertThat(coordinator.pollWritable(), nullValue());

        coordinator.applyStreamWindowUpdate(1, 200);
        coordinator.processAvailableCommands();
        assertThat(coordinator.pollWritable(), nullValue());

        coordinator.applyStreamWindowUpdate(1, 200);
        coordinator.processAvailableCommands();
        assertThat(coordinator.pollWritable().frame(), equalTo(blocked.frame()));
    }

    @Test
    void connectionWindowUpdateOverflowIsAConnectionFlowControlErrorAndStopsNormalWrites() {
        var coordinator = coordinator(Integer.MAX_VALUE - 1, 1, 100);
        coordinator.submit(task(data(1, "must not be sent")));
        coordinator.applyConnectionWindowUpdate(2, 7);
        coordinator.processAvailableCommands();

        var writable = coordinator.pollWritable();
        Http2Exception failure = writable.protocolError();
        assertThat(failure.errorType(), equalTo(Http2Level.CONNECTION));
        assertThat(failure.errorCode(), equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR));
        assertThat(failure.getMessage(), containsString("Credit overflow"));
        assertThat(
            writable.frame(),
            equalTo(new Http2GoAway(7, Http2ErrorCode.FLOW_CONTROL_ERROR.code(), null))
        );
        writable.complete();
        assertThat(coordinator.pollWritable(), nullValue());
    }

    @Test
    void streamWindowUpdateOverflowIsAStreamFlowControlErrorAndStopsThatStream() {
        var coordinator = coordinator(100, 1, Integer.MAX_VALUE - 1);
        var data = task(data(1, "must not be sent"));
        coordinator.submit(data);
        coordinator.applyStreamWindowUpdate(1, 2);
        coordinator.processAvailableCommands();

        var writable = coordinator.pollWritable();
        Http2Exception failure = writable.protocolError();
        assertThat(failure.errorType(), equalTo(Http2Level.STREAM));
        assertThat(failure.errorCode(), equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR));
        assertThat(failure.getMessage(), containsString("Credit overflow"));
        assertThat(
            writable.frame(),
            equalTo(new Http2ResetStreamFrame(1, Http2ErrorCode.FLOW_CONTROL_ERROR.code()))
        );
        writable.complete();
        assertThat(coordinator.pollWritable(), nullValue());
        assertThrows(IOException.class, () -> data.await(1, TimeUnit.SECONDS));
    }

    @Test
    void settingsWindowOverflowIsAConnectionFlowControlErrorAndIsNotAcknowledged() {
        var coordinator = coordinator(0, 1, Integer.MAX_VALUE - 1);
        coordinator.applyInitialWindowSizeChange(2, task(Http2Settings.ACK), 5);
        coordinator.processAvailableCommands();

        var writable = coordinator.pollWritable();
        Http2Exception failure = writable.protocolError();
        assertThat(failure.errorType(), equalTo(Http2Level.CONNECTION));
        assertThat(failure.errorCode(), equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR));
        assertThat(
            writable.frame(),
            equalTo(new Http2GoAway(5, Http2ErrorCode.FLOW_CONTROL_ERROR.code(), null))
        );
        writable.complete();
        assertThat(coordinator.pollWritable(), nullValue());
    }

    @Test
    void windowUpdatesForClosedStreamsAreIgnored() throws Exception {
        var coordinator = new Http2WriteCoordinator(0);
        coordinator.applyStreamWindowUpdate(1, 1);
        coordinator.processAvailableCommands();

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

        coordinator.applyStreamWindowUpdate(1, 5);
        coordinator.processAvailableCommands();

        var writable = coordinator.pollWritable();
        assertThat(writable.frame(), equalTo(data.frame()));
        writable.complete();
        assertThat(coordinator.streamState(1), nullValue());
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
