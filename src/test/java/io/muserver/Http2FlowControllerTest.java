package io.muserver;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2FlowControllerTest {

    @Test
    void initialCreditIsSetCorrectly() {
        var controller = new Http2FlowController(1, 1000);
        assertThat(controller.withdrawIfCan(1000), is(true));
        assertThat(controller.withdrawIfCan(1), is(false));
    }

    @Test
    void windowUpdateIncreasesCredit() throws Http2Exception {
        var controller = new Http2FlowController(1, 1000);
        var update = new Http2WindowUpdate(1, 500);
        controller.applyWindowUpdate(update);

        assertThat(controller.withdrawIfCan(1500), is(true));
        assertThat(controller.withdrawIfCan(1), is(false));
    }

    @Test
    void windowUpdateOverflowThrowsException() {
        var controller = new Http2FlowController(1, Integer.MAX_VALUE - 1);
        var update = new Http2WindowUpdate(1, 2);
        var exception = assertThrows(Http2Exception.class, () -> controller.applyWindowUpdate(update));
        assertThat(exception.errorType(), equalTo(Http2Level.STREAM));
        assertThat(exception.errorCode(), equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR));
        assertThat(exception.getMessage(), containsString("Credit overflow"));
    }


    @Test
    void settingsChangeAdjustsCredit() throws Http2Exception {
        var controller = new Http2FlowController(1, 1000);
        var oldSettings = getOldSettings(1000);
        var newSettings = getOldSettings(2000);

        controller.applySettingsChange(oldSettings, newSettings);
        assertThat(controller.withdrawIfCan(2000), is(true));
        assertThat(controller.withdrawIfCan(1), is(false));
    }

    @Test
    void ifCreditBecomesNegativeDueToASettingsChangeThenNoWithdrawlsAllowedUntilEnoughPaidBack() throws Http2Exception {
        var controller = new Http2FlowController(1, 1000);
        // balance is 1000
        assertThat(controller.withdrawIfCan(500), is(true));
        // balance is 500
        var oldSettings = getOldSettings(1000);
        var newSettings = getOldSettings(200);
        controller.applySettingsChange(oldSettings, newSettings);

        // balance is -300
        assertThat(controller.withdrawIfCan(1), is(false));

        controller.applyWindowUpdate(new Http2WindowUpdate(1, 200));
        // balance is -100

        assertThat(controller.withdrawIfCan(1), is(false));

        controller.applyWindowUpdate(new Http2WindowUpdate(1, 200));
        // balance is 100

        assertThat(controller.withdrawIfCan(100), is(true));

        // balance is 0
        assertThat(controller.withdrawIfCan(1), is(false));
    }

    private static Http2Settings getOldSettings(int initialWindowSize) {
        return new Http2Settings(false, 100, 100, initialWindowSize, 100, 100);
    }

    @Test
    void settingsChangeOverflowThrowsException() {
        var controller = new Http2FlowController(1, Integer.MAX_VALUE - 1);
        var oldSettings = getOldSettings(1000);
        var newSettings = getOldSettings(Integer.MAX_VALUE - 1000);

        var e = assertThrows(Http2Exception.class, () -> controller.applySettingsChange(oldSettings, newSettings));
        assertThat(e.errorType(), equalTo(Http2Level.STREAM));
        assertThat(e.errorCode(), equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR));
        assertThat(e.getMessage(), containsString("Credit overflow"));
    }

    @Test
    void withdrawIfCanReturnsCorrectly() {
        var controller = new Http2FlowController(1, 1000);
        assertThat(controller.withdrawIfCan(0), is(true));
        assertThat(controller.withdrawIfCan(500), is(true));
        assertThat(controller.withdrawIfCan(500), is(true));
        assertThat(controller.withdrawIfCan(1), is(false));
    }

    @Test
    void negativeWithdrawalIsNotAllowed() {
        var controller = new Http2FlowController(1, 1000);
        assertThrows(IllegalArgumentException.class, () -> controller.withdrawIfCan(-1));
    }
}