package io.muserver;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2IncomingFlowControllerTest {

    @Test
    void initialCreditIsSetCorrectly() {
        var controller = new Http2IncomingFlowController(1, 1000);
        assertThat(controller.withdrawIfCan(1000), is(true));
        assertThat(controller.withdrawIfCan(1), is(false));
    }


    @Test
    void settingsChangeAdjustsCredit() throws Http2Exception {
        var controller = new Http2IncomingFlowController(1, 1000);
        var oldSettings = getOldSettings(1000);
        var newSettings = getOldSettings(2000);

        controller.applySettingsChange(oldSettings, newSettings);
        assertThat(controller.withdrawIfCan(2000), is(true));
        assertThat(controller.withdrawIfCan(1), is(false));
    }

    @Test
    void ifCreditBecomesNegativeDueToASettingsChangeThenNoWithdrawlsAllowedUntilEnoughPaidBack() throws Http2Exception {
        var controller = new Http2IncomingFlowController(1, 1000);
        // ...now balance is 1000

        assertThat(controller.withdrawIfCan(500), is(true));
        // ...now balance is 500

        var oldSettings = getOldSettings(1000);
        var newSettings = getOldSettings(200);
        controller.applySettingsChange(oldSettings, newSettings);
        // ...now balance is -300

        // can't withdraw from negabite balance
        assertThat(controller.withdrawIfCan(1), is(false));

        controller.incrementCredit(200);
        // ...now balance is -100

        assertThat(controller.withdrawIfCan(1), is(false));

        controller.incrementCredit(200);
        // ...now balance is 100

        assertThat(controller.withdrawIfCan(100), is(true));
        // ...now balance is 0

        assertThat(controller.withdrawIfCan(1), is(false));
    }

    private static Http2Settings getOldSettings(int initialWindowSize) {
        return new Http2Settings(false, 100, 100, initialWindowSize, 100, 100);
    }

    @Test
    void settingsChangeOverflowThrowsException() {
        var controller = new Http2IncomingFlowController(1, Integer.MAX_VALUE - 1);
        var oldSettings = getOldSettings(1000);
        var newSettings = getOldSettings(Integer.MAX_VALUE - 1000);

        var e = assertThrows(Http2Exception.class, () -> controller.applySettingsChange(oldSettings, newSettings));
        assertThat(e.errorType(), equalTo(Http2Level.STREAM));
        assertThat(e.errorCode(), equalTo(Http2ErrorCode.FLOW_CONTROL_ERROR));
        assertThat(e.getMessage(), containsString("Credit overflow"));
    }

    @Test
    void withdrawIfCanReturnsCorrectly() {
        var controller = new Http2IncomingFlowController(1, 1000);
        assertThat(controller.withdrawIfCan(0), is(true));
        assertThat(controller.withdrawIfCan(500), is(true));
        assertThat(controller.withdrawIfCan(500), is(true));
        assertThat(controller.withdrawIfCan(1), is(false));
    }

    @Test
    void negativeWithdrawalIsNotAllowed() {
        var controller = new Http2IncomingFlowController(1, 1000);
        assertThrows(IllegalArgumentException.class, () -> controller.withdrawIfCan(-1));
    }
}