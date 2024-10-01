package io.muserver.rest;

import jakarta.ws.rs.core.NewCookie;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class NewCookieHeaderDelegateTest {

    private final NewCookieHeaderDelegate delegate = new NewCookieHeaderDelegate();

    @Test
    public void canRoundTrip() {
        NewCookie newCookie = new NewCookie("Blah", "ha%20ha", "/what", "example.org", "Comments are ignored", 1234567, true, true);
        String headerValue = delegate.toString(newCookie);
        assertThat(headerValue, equalTo("Blah=ha%20ha; Domain=example.org; Path=/what; Max-Age=1234567; Secure; HttpOnly"));
        NewCookie recreated = delegate.fromString(headerValue);
        assertThat(recreated.getName(), equalTo("Blah"));
        assertThat(recreated.getValue(), equalTo("ha%20ha"));
        assertThat(recreated.getPath(), equalTo("/what"));
        assertThat(recreated.getDomain(), equalTo("example.org"));
        assertThat(recreated.getMaxAge(), equalTo(1234567));
        assertThat(recreated.isHttpOnly(), is(true));
        assertThat(recreated.isSecure(), is(true));
        assertThat(recreated.getComment(), is(nullValue()));
        assertThat(recreated.getVersion(), is(1));
    }

}