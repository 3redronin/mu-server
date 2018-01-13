package io.muserver.rest;

import org.junit.Test;

import javax.ws.rs.core.Cookie;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class CookieHeaderDelegateTest {

    private final CookieHeaderDelegate delegate = new CookieHeaderDelegate();

    @Test
    public void canRoundTrip() {
        Cookie clientCookie = new Cookie("Blah", "ha ha", "/what", "example.org");
        String headerValue = delegate.toString(clientCookie);
        assertThat(headerValue, equalTo("Blah=ha%20ha"));

        Cookie recreated = delegate.fromString(headerValue);
        assertThat(recreated.getName(), equalTo("Blah"));
        assertThat(recreated.getValue(), equalTo("ha ha"));
        assertThat(recreated.getPath(), is(nullValue())); // these are ignored for client cookies
        assertThat(recreated.getDomain(), is(nullValue())); // these are ignored for client cookies
        assertThat(recreated.getVersion(), is(1));
    }

}