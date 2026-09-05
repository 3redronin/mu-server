package io.muserver;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class WebsocketPingTrackerTest {

    private final WebsocketPingTracker tracker = new WebsocketPingTracker();

    @Test
    void overlappingPingsRetainTheirOwnMonotonicSendPoints() {
        ByteBuffer firstPing = tracker.newPingPayload(1_000_000L);
        ByteBuffer secondPing = tracker.newPingPayload(4_000_000L);

        assertThat(tracker.pongLatencyMillis(secondPing, 6_000_000L), is(2L));
        assertThat(tracker.pongLatencyMillis(firstPing, 8_000_000L), is(7L));
        assertThat(tracker.pongLatencyMillis(firstPing, 9_000_000L), nullValue());
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
            .putLong(123L)
            .putLong(2L)
            .flip();

        assertThat(tracker.pongLatencyMillis(tamperedTimestamp, 3L), nullValue());
        assertThat(tracker.pongLatencyMillis(peerChosenTimestamp, 3L), nullValue());
        assertThat(tracker.pongLatencyMillis(ByteBuffer.allocate(15), 2L), nullValue());
    }
    @Test
    void oldAndOtherConnectionPongsAreNotSamples() {
        ByteBuffer oldest = tracker.newPingPayload(1L);
        for (int i = 0; i < WebsocketPingTracker.MAX_OUTSTANDING; i++) {
            tracker.newPingPayload(2L + i);
        }
        assertThat(tracker.pongLatencyMillis(oldest, 100L), nullValue());
        ByteBuffer current = tracker.newPingPayload(100L);
        assertThat(new WebsocketPingTracker().pongLatencyMillis(current, 101L), nullValue());
        assertThat(tracker.pongLatencyMillis(current, 102L), is(0L));
        assertThat(tracker.pongLatencyMillis(current, 103L), nullValue());
    }

}
