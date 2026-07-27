package io.muserver;

import org.junit.jupiter.api.Test;

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
    void anAbsoluteDeadlineRetainsASubMillisecondRemainder() {
        long deadline = MonotonicTime.deadlineAfter(100L, 1_000_000L);

        assertThat(
            MonotonicTime.nanosUntil(deadline, 101L),
            is(999_999L)
        );
    }
}
