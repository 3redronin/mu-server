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
        assertThat(MonotonicTime.elapsedMillis(now, deadline), is(0L));
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

    @Test
    void anAbsoluteDeadlineRetainsASubMillisecondRemainder() {
        long deadline = MonotonicTime.deadlineAfter(100L, 1_000_000L);

        assertThat(
            MonotonicTime.nanosUntil(deadline, 101L),
            is(999_999L)
        );
    }

    @Test
    void elapsedMillisecondsAreCalculatedFromMonotonicPoints() {
        long start = 123L;

        assertThat(MonotonicTime.elapsedMillis(start, start + 999_999L), is(0L));
        assertThat(MonotonicTime.elapsedMillis(start, start + 1_000_000L), is(1L));
        assertThat(MonotonicTime.elapsedMillis(start, start - 1L), is(0L));
    }
}
