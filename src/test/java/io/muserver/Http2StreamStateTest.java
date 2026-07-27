package io.muserver;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static io.muserver.Http2StreamState.CLOSED;
import static io.muserver.Http2StreamState.HALF_CLOSED_LOCAL;
import static io.muserver.Http2StreamState.HALF_CLOSED_REMOTE;
import static io.muserver.Http2StreamState.OPEN;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Http2StreamStateTest {

    @Test
    void remoteEndStreamTransitionsExactlyWhenTheRemoteSideIsOpen() {
        assertThat(OPEN.remoteEndStream(), equalTo(HALF_CLOSED_REMOTE));
        assertThat(HALF_CLOSED_LOCAL.remoteEndStream(), equalTo(CLOSED));

        assertThrows(IllegalStateException.class, HALF_CLOSED_REMOTE::remoteEndStream);
        assertThrows(IllegalStateException.class, CLOSED::remoteEndStream);
    }

    @Test
    void localEndStreamTransitionsExactlyWhenTheLocalSideIsOpen() {
        assertThat(OPEN.localEndStream(), equalTo(HALF_CLOSED_LOCAL));
        assertThat(HALF_CLOSED_REMOTE.localEndStream(), equalTo(CLOSED));

        assertThrows(IllegalStateException.class, HALF_CLOSED_LOCAL::localEndStream);
        assertThrows(IllegalStateException.class, CLOSED::localEndStream);
    }

    @Test
    void capabilityQueriesAgreeWithEveryState() {
        for (var state : Http2StreamState.values()) {
            assertThat(
                state + " receive capability",
                state.canReceiveEndStream(),
                equalTo(EnumSet.of(OPEN, HALF_CLOSED_LOCAL).contains(state))
            );
            assertThat(
                state + " send capability",
                state.canSendEndStream(),
                equalTo(EnumSet.of(OPEN, HALF_CLOSED_REMOTE).contains(state))
            );
        }
    }

    @Test
    void resetClosesEveryState() {
        for (var state : Http2StreamState.values()) {
            assertThat(state + " reset", state.reset(), equalTo(CLOSED));
        }
    }
}
