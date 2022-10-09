package io.muserver.rest;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThrows;

public class DateHeaderDelegateTest {
    private final DateHeaderDelegate delegate = new DateHeaderDelegate();

    @Test
    public void throwsIfValueNull() {
        assertThrows(IllegalArgumentException.class, () -> delegate.fromString(null));
        assertThrows(IllegalArgumentException.class, () -> delegate.toString(null));
    }

    @Test
    public void throwsIfInvalidDate() {
        assertThrows(IllegalArgumentException.class, () -> delegate.fromString("I'm a bad date"));
    }

    @Test
    public void canRoundTrip() {
        assertThat(delegate.toString(delegate.fromString("Sun, 9 Oct 2022 14:43:10 GMT")), equalTo("Sun, 9 Oct 2022 14:43:10 GMT"));
    }

}