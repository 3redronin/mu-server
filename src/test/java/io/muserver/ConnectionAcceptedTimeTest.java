package io.muserver;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class ConnectionAcceptedTimeTest {

    @Test
    void exposesEpochTimeButCalculatesElapsedTimeMonotonically() {
        Instant epochTime = Instant.parse("2026-07-28T00:00:00Z");
        long acceptedNanos = Long.MAX_VALUE - 500_000L;
        long readyNanos = acceptedNanos + TimeUnit.MILLISECONDS.toNanos(2L);
        ConnectionAcceptedTime accepted =
            ConnectionAcceptedTime.of(epochTime, acceptedNanos);

        assertThat(accepted.instant(), is(epochTime));
        assertThat(accepted.elapsedMillisUntil(readyNanos), is(2L));
    }
}
