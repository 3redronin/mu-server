package io.muserver;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

class MonotonicTimeTest {

    @Test
    void deadlinesAndRemainingTimeWorkAcrossSignedLongWraparound() {
        long now = Long.MAX_VALUE - 5L;
        long deadline = MonotonicTime.deadlineAfter(now, 10L);

        assertThat(deadline, is(Long.MIN_VALUE + 4L));
        assertThat(MonotonicTime.nanosUntil(deadline, now), is(10L));
        assertThat(MonotonicTime.isAfter(deadline, now), is(true));
    }

    @Test
    void negativeDurationsExpireImmediately() {
        assertThat(MonotonicTime.deadlineAfter(123L, -1L), is(123L));
    }

    @Test
    void anOlderConcurrentPublicationCannotMoveActivityBackwards() {
        var latest = new AtomicLong(100L);

        MonotonicTime.publishLatest(latest, 200L);
        MonotonicTime.publishLatest(latest, 150L);

        assertThat(latest.get(), is(200L));

        latest.set(Long.MAX_VALUE - 5L);
        MonotonicTime.publishLatest(latest, Long.MIN_VALUE + 4L);
        MonotonicTime.publishLatest(latest, Long.MAX_VALUE - 2L);

        assertThat(latest.get(), is(Long.MIN_VALUE + 4L));
    }
}
