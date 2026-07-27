package io.muserver;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class WebsocketPingTrackerTest {

    private static final byte[] CONNECTION_SECRET =
        new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
    private final WebsocketPingTracker tracker = new WebsocketPingTracker(
        CONNECTION_SECRET
    );

    @Test
    void overlappingPingsRetainTheirOwnMonotonicSendPoints() {
        ByteBuffer firstPing = tracker.newPingPayload(1_000_000L);
        ByteBuffer secondPing = tracker.newPingPayload(4_000_000L);

        assertThat(tracker.pongLatencyMillis(secondPing, 6_000_000L), is(2L));
        assertThat(tracker.pongLatencyMillis(firstPing, 8_000_000L), is(7L));
        assertThat(firstPing.getLong(0), is(1_000_000L));
    }

    @Test
    void pongPayloadIsReadFromItsCurrentPositionWithoutAdvancingIt() {
        ByteBuffer ping = tracker.newPingPayload(2_000_000L);
        ByteBuffer framed = ByteBuffer.allocate(20)
            .putInt(123)
            .put(ping)
            .flip()
            .position(4);

        assertThat(tracker.pongLatencyMillis(framed, 5_000_000L), is(3L));
        assertThat(framed.position(), is(4));
    }

    @Test
    void forgedOrMalformedPayloadsAreNotLatencyPongs() {
        ByteBuffer tamperedTimestamp = tracker.newPingPayload(1L);
        tamperedTimestamp.putLong(0, 2L);
        ByteBuffer peerChosenTimestamp = ByteBuffer.allocate(16)
            .put(CONNECTION_SECRET)
            .putLong(2L)
            .flip();

        assertThat(tracker.pongLatencyMillis(tamperedTimestamp, 3L), nullValue());
        assertThat(tracker.pongLatencyMillis(peerChosenTimestamp, 3L), nullValue());
        assertThat(tracker.pongLatencyMillis(ByteBuffer.allocate(15), 2L), nullValue());
    }
}
