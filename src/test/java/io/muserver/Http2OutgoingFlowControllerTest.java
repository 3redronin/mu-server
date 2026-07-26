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
    void settingsChangeAdjustsCredit() throws Http2Exception {
        var controller = new Http2OutgoingFlowController(1, 1000);
        var oldSettings = getSettings(1000);
        var newSettings = getSettings(2000);

        controller.applySettingsChange(oldSettings, newSettings);
        assertThat(controller.withdrawIfCan(2000), is(true));
        assertThat(controller.withdrawIfCan(1), is(false));
    }

    @Test
    void settingsChangeCanMakeCreditNegativeUntilWindowUpdatesArrive() throws Http2Exception {
        var controller = new Http2OutgoingFlowController(1, 1000);

        assertThat(controller.withdrawIfCan(500), is(true));

        controller.applySettingsChange(getSettings(1000), getSettings(200));
        assertThat(controller.withdrawIfCan(1), is(false));

        controller.applyWindowUpdate(new Http2WindowUpdate(1, 200));
        assertThat(controller.withdrawIfCan(1), is(false));

        controller.applyWindowUpdate(new Http2WindowUpdate(1, 200));
        assertThat(controller.withdrawIfCan(100), is(true));
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

    @Test
    void terminatedStreamsCannotReserveCredit() {
        var controller = new Http2OutgoingFlowController(1, 10);

        controller.terminate();

        assertThat(controller.withdrawIfCan(1), is(false));
        assertThat(controller.withdrawUpTo(1), equalTo(0));
        assertThat(controller.credit(), equalTo(10));
    }

    @Test
    void withdrawUpToUsesTheAvailableCredit() {
        var controller = new Http2OutgoingFlowController(1, 3);

        assertThat(controller.withdrawUpTo(5), equalTo(3));
        assertThat(controller.withdrawUpTo(1), equalTo(0));
    }

    private static Http2Settings getSettings(int initialWindowSize) {
        return new Http2Settings(false, 100, 100, initialWindowSize, 100, 100);
    }
}
