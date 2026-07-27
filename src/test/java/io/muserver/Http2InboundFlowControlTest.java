package io.muserver;

import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2InboundFlowControlTest {

    @Test
    void reservesConnectionAndStreamCreditAtomically() {
        var flow = new Http2InboundFlowControl(10);
        flow.openStream(1, 4);

        assertThat(flow.reserve(1, 4).error(), nullValue());
        Http2Exception streamError = error(flow.reserve(1, 1));
        assertThat(streamError.errorType(), equalTo(Http2Level.STREAM));
        assertThat(streamError.errorCode(), equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR));

        // The failed stream reservation restored the connection reservation.
        flow.openStream(3, 10);
        assertThat(flow.reserve(3, 6).error(), nullValue());
    }

    @Test
    void enforcesTheConnectionWindowBeforeTheStreamWindow() {
        var flow = new Http2InboundFlowControl(4);
        flow.openStream(1, 10);

        Http2Exception error = error(flow.reserve(1, 5));
        assertThat(error.errorType(), equalTo(Http2Level.CONNECTION));
        assertThat(error.errorCode(), equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR));
    }

    @Test
    void batchesUpdatesAtHalfTheAdvertisedWindow() {
        var flow = new Http2InboundFlowControl(65_535);
        flow.openStream(1, 1_000);
        assertThat(flow.reserve(1, 1_000).error(), nullValue());

        var firstReturn = flow.returnCredit(1, 499, true);
        assertThat(firstReturn.connectionUpdate(), equalTo(0));
        assertThat(firstReturn.streamUpdate(), equalTo(0));

        var secondReturn = flow.returnCredit(1, 1, true);
        assertThat(secondReturn.connectionUpdate(), equalTo(0));
        assertThat(secondReturn.streamUpdate(), equalTo(500));
    }

    @Test
    void returnsConnectionAndStreamUpdatesTogether() {
        var flow = new Http2InboundFlowControl(65_535);
        flow.openStream(1, 65_535);
        assertThat(flow.reserve(1, 40_000).error(), nullValue());

        var returned = flow.returnCredit(1, 40_000, true);
        assertThat(returned.error(), nullValue());
        assertThat(returned.connectionUpdate(), equalTo(40_000));
        assertThat(returned.streamUpdate(), equalTo(40_000));
    }

    @Test
    void dataRejectedForAClosedStreamReturnsItsConnectionCredit() {
        var flow = new Http2InboundFlowControl(65_535);
        flow.openStream(1, 65_535);
        flow.closeStream(1);

        var rejected = flow.reserve(1, 32_768);
        Http2Exception error = error(rejected);
        assertThat(error.errorType(), equalTo(Http2Level.STREAM));
        assertThat(error.errorCode(), equalTo(Http2ErrorCode.STREAM_CLOSED));
        assertThat(rejected.connectionUpdate(), equalTo(32_768));
        assertThat(rejected.streamUpdate(), equalTo(0));
    }

    @Test
    void duplicateOpenDoesNotReplaceExistingCredit() {
        var flow = new Http2InboundFlowControl(10);
        flow.openStream(1, 4);

        assertThrows(IllegalStateException.class, () -> flow.openStream(1, 10));
        assertThat(flow.reserve(1, 4).error(), nullValue());
        assertThat(
            error(flow.reserve(1, 1)).errorCode(),
            equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR)
        );
    }

    private static Http2Exception error(Http2InboundFlowControl.Result result) {
        return Objects.requireNonNull(result.error());
    }
}
