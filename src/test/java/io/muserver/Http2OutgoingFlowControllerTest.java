package io.muserver;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2OutgoingFlowControllerTest {

    @Test
    void initialCreditIsSetCorrectly() {
        var controller = new Http2OutgoingFlowController(1, 1000);
        assertThat(controller.withdrawIfCan(1000), is(true));
        assertThat(controller.withdrawIfCan(1), is(false));
    }

    @Test
    void windowUpdateIncreasesCredit() throws Http2Exception {
        var controller = new Http2OutgoingFlowController(1, 1000);
        var update = new Http2WindowUpdate(1, 500);
        controller.applyWindowUpdate(update);

        assertThat(controller.withdrawIfCan(1500), is(true));
        assertThat(controller.withdrawIfCan(1), is(false));
    }

    @Test
    void windowUpdateOverflowThrowsException() {
        var controller = new Http2OutgoingFlowController(1, Integer.MAX_VALUE - 1);
        var update = new Http2WindowUpdate(1, 2);
        var exception = assertThrows(Http2Exception.class, () -> controller.applyWindowUpdate(update));
        assertThat(exception.errorType(), equalTo(Http2Level.STREAM));
        assertThat(exception.errorCode(), equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR));
        assertThat(exception.getMessage(), containsString("Credit overflow"));
    }

    @Test
    void withdrawIfCanReturnsCorrectly() {
        var controller = new Http2OutgoingFlowController(1, 1000);
        assertThat(controller.withdrawIfCan(0), is(true));
        assertThat(controller.withdrawIfCan(500), is(true));
        assertThat(controller.withdrawIfCan(500), is(true));
        assertThat(controller.withdrawIfCan(1), is(false));
    }

    @Test
    void negativeWithdrawalIsNotAllowed() {
        var controller = new Http2OutgoingFlowController(1, 1000);
        assertThrows(IllegalArgumentException.class, () -> controller.withdrawIfCan(-1));
    }
}