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
        NewCookie newCookie = new NewCookie.Builder("Blah")
            .value("ha%20ha")
            .path("/what")
            .domain("example.org")
            .comment("Comments are serialized")
            .maxAge(1234567)
            .secure(true)
            .httpOnly(true)
            .sameSite(NewCookie.SameSite.STRICT)
            .build();
        String headerValue = delegate.toString(newCookie);
        assertThat(headerValue, equalTo("Blah=ha%20ha; Domain=example.org; Path=/what; Max-Age=1234567; SameSite=Strict; Secure; HttpOnly; Comment=\"Comments are serialized\""));
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
    public void quotesAndEscapesComments() {
        NewCookie cookie = new NewCookie.Builder("name")
            .value("value")
            .comment("A \"quoted\" \\ comment")
            .build();

        assertThat(delegate.toString(cookie), is("name=value; Comment=\"A \\\"quoted\\\" \\\\ comment\""));
    }

    @Test
    public void rejectsLineBreaksInComments() {
        NewCookie cookie = new NewCookie.Builder("name")
            .value("value")
            .comment("unsafe\r\ncomment")
            .build();

        assertThrows(IllegalArgumentException.class, () -> delegate.toString(cookie));
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
    public void lastDuplicateSameSiteAttributeWins() {
        NewCookie parsed = delegate.fromString(
            "session=abc; SameSite=Lax; SameSite=None");

        assertThat(parsed.getSameSite(), is(NewCookie.SameSite.NONE));
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

        assertThat(delegate.toString(cookie), equalTo("session=; Max-Age=0"));
    }

    @Test
    public void positiveMaxAgeSerializesMaxAge() {
        NewCookie cookie = new NewCookie.Builder("persistent")
            .value("abc")
            .maxAge(60)
            .secure(false)
            .build();

        assertThat(delegate.toString(cookie), equalTo("persistent=abc; Max-Age=60"));
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
        assertThat(delegate.toString(httpOnlyOnly), equalTo("name=value; HttpOnly"));
    }

    @Test
    public void nullValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> delegate.toString(null));
        assertThrows(IllegalArgumentException.class, () -> delegate.fromString(null));
    }

}
