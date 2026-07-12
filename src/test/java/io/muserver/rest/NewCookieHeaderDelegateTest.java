package io.muserver.rest;

import jakarta.ws.rs.core.NewCookie;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    public void sessionCookieOmitsExpiryAttributes() {
        NewCookie cookie = new NewCookie("session", "abc");

        String headerValue = delegate.toString(cookie);

        assertThat(headerValue, equalTo("session=abc"));
        assertThat(headerValue, not(containsString("Max-Age=")));
        assertThat(headerValue, not(containsString("Expires=")));
    }

    @Test
    public void explicitSessionCookieOmitsExpiryAttributes() {
        NewCookie cookie = new NewCookie(
            "session", "abc", null, null, null,
            NewCookie.DEFAULT_MAX_AGE, false
        );

        String headerValue = delegate.toString(cookie);

        assertThat(headerValue, equalTo("session=abc"));
        assertThat(headerValue, not(containsString("Max-Age=")));
        assertThat(headerValue, not(containsString("Expires=")));
    }

    @Test
    public void zeroMaxAgeSerializesAsDeletionCookie() {
        NewCookie cookie = new NewCookie(
            "session", "", null, null, null, 0, false
        );

        assertThat(delegate.toString(cookie), equalTo("session=; Max-Age=0"));
    }

    @Test
    public void positiveMaxAgeSerializesMaxAge() {
        NewCookie cookie = new NewCookie(
            "persistent", "abc", null, null, null, 60, false
        );

        assertThat(delegate.toString(cookie), equalTo("persistent=abc; Max-Age=60"));
    }

    @Test
    public void nullPathAndDomainAreOmitted() {
        NewCookie cookie = new NewCookie(
            "name", "value", null, null, null,
            NewCookie.DEFAULT_MAX_AGE, false
        );

        String headerValue = delegate.toString(cookie);

        assertThat(headerValue, not(containsString("Path=")));
        assertThat(headerValue, not(containsString("Domain=")));
    }

    @Test
    public void secureAndHttpOnlyAreIndependent() {
        NewCookie secureOnly = new NewCookie(
            "name", "value", null, null, null,
            NewCookie.DEFAULT_MAX_AGE, true, false
        );
        NewCookie httpOnlyOnly = new NewCookie(
            "name", "value", null, null, null,
            NewCookie.DEFAULT_MAX_AGE, false, true
        );

        assertThat(delegate.toString(secureOnly), equalTo("name=value; Secure"));
        assertThat(delegate.toString(httpOnlyOnly), equalTo("name=value; HttpOnly"));
    }

    @Test
    public void nullValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> delegate.toString(null));
        assertThrows(IllegalArgumentException.class, () -> delegate.fromString(null));
    }

}
