package io.muserver.rest;

import jakarta.ws.rs.core.NewCookie;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThrows;

public class NewCookieHeaderDelegateTest {

    private final NewCookieHeaderDelegate delegate = new NewCookieHeaderDelegate();

    @Test
    public void canRoundTrip() {
        NewCookie newCookie = new NewCookie.Builder("Blah")
            .value("ha%20ha")
            .path("/what")
            .domain("example.org")
            .comment("Comments are ignored")
            .maxAge(1234567)
            .secure(true)
            .httpOnly(true)
            .sameSite(NewCookie.SameSite.STRICT)
            .build();
        String headerValue = delegate.toString(newCookie);
        assertThat(headerValue, startsWith("Blah=ha%20ha; Max-Age=1234567; Expires="));
        assertThat(headerValue, endsWith("; Path=/what; Domain=example.org; Secure; HTTPOnly; SameSite=Strict"));
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
        assertThat(recreated.getSameSite(), is(NewCookie.SameSite.STRICT));
    }

    @Test
    public void allSameSiteValuesRoundTrip() {
        for (NewCookie.SameSite sameSite : NewCookie.SameSite.values()) {
            NewCookie cookie = new NewCookie.Builder("session")
                .value("abc")
                .sameSite(sameSite)
                .build();

            String serialized = delegate.toString(cookie);
            NewCookie parsed = delegate.fromString(serialized);

            assertThat(serialized, containsString("SameSite="));
            assertThat(parsed.getSameSite(), is(sameSite));
        }
    }

    @Test
    public void sameSiteParsingIsCaseInsensitive() {
        assertThat(delegate.fromString("session=abc; SameSite=sTrIcT").getSameSite(),
            is(NewCookie.SameSite.STRICT));
    }

    @Test
    public void invalidSameSiteValuesAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> delegate.fromString("session=abc; SameSite=somewhere"));
    }

    @Test
    public void cookieNamedSameSiteIsNotMistakenForAnAttribute() {
        NewCookie parsed = delegate.fromString("SameSite=abc; Path=/");

        assertThat(parsed.getName(), is("SameSite"));
        assertThat(parsed.getValue(), is("abc"));
        assertThat(parsed.getPath(), is("/"));
        assertThat(parsed.getSameSite(), is(nullValue()));
    }

    @Test
    public void sessionCookieOmitsExpiryAttributes() {
        NewCookie cookie = new NewCookie.Builder("session")
            .value("abc")
            .build();

        String headerValue = delegate.toString(cookie);

        assertThat(headerValue, equalTo("session=abc"));
        assertThat(headerValue, not(containsString("Max-Age=")));
        assertThat(headerValue, not(containsString("Expires=")));
    }

    @Test
    public void explicitSessionCookieOmitsExpiryAttributes() {
        NewCookie cookie = new NewCookie.Builder("session")
            .value("abc")
            .maxAge(NewCookie.DEFAULT_MAX_AGE)
            .secure(false)
            .build();

        String headerValue = delegate.toString(cookie);

        assertThat(headerValue, equalTo("session=abc"));
        assertThat(headerValue, not(containsString("Max-Age=")));
        assertThat(headerValue, not(containsString("Expires=")));
    }

    @Test
    public void zeroMaxAgeSerializesAsDeletionCookie() {
        NewCookie cookie = new NewCookie.Builder("session")
            .value("")
            .maxAge(0)
            .secure(false)
            .build();

        String headerValue = delegate.toString(cookie);

        assertThat(headerValue, startsWith("session=; Max-Age=0"));
        assertThat(headerValue, containsString("Expires="));
    }

    @Test
    public void positiveMaxAgeSerializesMaxAgeAndExpiry() {
        NewCookie cookie = new NewCookie.Builder("persistent")
            .value("abc")
            .maxAge(60)
            .secure(false)
            .build();

        String headerValue = delegate.toString(cookie);

        assertThat(headerValue, startsWith("persistent=abc; Max-Age=60; Expires="));
    }

    @Test
    public void nullPathAndDomainAreOmitted() {
        NewCookie cookie = new NewCookie.Builder("name")
            .value("value")
            .maxAge(NewCookie.DEFAULT_MAX_AGE)
            .secure(false)
            .build();

        String headerValue = delegate.toString(cookie);

        assertThat(headerValue, not(containsStringIgnoringCase("Path=")));
        assertThat(headerValue, not(containsStringIgnoringCase("Domain=")));
        assertThat(headerValue, not(containsStringIgnoringCase("SameSite=")));
    }

    @Test
    public void secureAndHttpOnlyAreIndependent() {
        NewCookie secureOnly = new NewCookie.Builder("name")
            .value("value")
            .maxAge(NewCookie.DEFAULT_MAX_AGE)
            .secure(true)
            .httpOnly(false)
            .build();
        NewCookie httpOnlyOnly = new NewCookie.Builder("name")
            .value("value")
            .maxAge(NewCookie.DEFAULT_MAX_AGE)
            .secure(false)
            .httpOnly(true)
            .build();

        assertThat(delegate.toString(secureOnly), equalTo("name=value; Secure"));
        assertThat(delegate.toString(httpOnlyOnly), equalTo("name=value; HTTPOnly"));
    }

    @Test
    public void throwsIfValueNull() {
        assertThrows(IllegalArgumentException.class, () -> delegate.fromString(null));
        assertThrows(IllegalArgumentException.class, () -> delegate.toString(null));
    }

}
