package io.muserver;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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
    void waitUntilWithdrawReturnsWhenCreditArrives() throws Exception {
        var controller = new Http2OutgoingFlowController(1, 0);

        var withdrawn = CompletableFuture.supplyAsync(() -> {
            try {
                return controller.waitUntilWithdraw(5, 1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });

        Thread.sleep(50);
        controller.incrementCredit(10);

        assertThat(withdrawn.get(1, TimeUnit.SECONDS), is(true));
        assertThat(controller.withdrawIfCan(5), is(true));
        assertThat(controller.withdrawIfCan(1), is(false));
    }

    @Test
    void negativeWithdrawalIsNotAllowed() {
        var controller = new Http2OutgoingFlowController(1, 1000);
        assertThrows(IllegalArgumentException.class, () -> controller.withdrawIfCan(-1));
    }

    private static Http2Settings getSettings(int initialWindowSize) {
        return new Http2Settings(false, 100, 100, initialWindowSize, 100, 100);
    }
}