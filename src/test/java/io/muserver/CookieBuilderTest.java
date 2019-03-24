package io.muserver;

import org.junit.Assert;
import org.junit.Test;

import static io.muserver.CookieBuilder.newCookie;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

public class CookieBuilderTest {

    @Test
    public void throwsWithNoName() {
        try {
            newCookie().withValue("value").build();
            Assert.fail("Should throw");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), is("A cookie name must be specified"));
        }
    }

    @Test
    public void throwsWithNoValue() {
        try {
            newCookie().withName("cookiename").build();
            Assert.fail("Should throw");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), is("A cookie value must be specified"));
        }
    }

    @Test
    public void invalidValuesAreRejected() {
        try {
            newCookie().withValue("an invalid value because of the spaces");
            Assert.fail("Should throw");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), is("A cookie value can only be ASCII characters excluding control " +
                "characters, whitespace, quotes, commas, semicolons and backslashes. Consider using " +
                "CookieBuilder.withUrlEncodedValue instead."));
        }
    }

    @Test
    public void invalidNamesAreRejected() {
        try {
            newCookie().withName("an invalid name because of the spaces");
            Assert.fail("Should throw");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), is("A cookie name can only be alphanumeric ASCII characters or any " +
                "of \"!#$%&'*+-.^_`|~\" (excluding quotes)"));
        }
    }

    @Test
    public void itBuildsCookies() {
        Cookie cookie = newCookie()
            .withName("Name0123456789!#$%&'*+-.^_`|~")
            .withValue("Value0123456789!#$%&'()*+-./:<=>?@[]^_`{|}~")
            .withMaxAgeInSeconds(20)
            .withDomain("example.org")
            .withPath("/static")
            .secure(true)
            .httpOnly(true)
            .build();
        assertThat(cookie.name(), is("Name0123456789!#$%&'*+-.^_`|~"));
        assertThat(cookie.value(), is("Value0123456789!#$%&'()*+-./:<=>?@[]^_`{|}~"));
        assertThat(cookie.maxAge(), is(20L));
        assertThat(cookie.domain(), is("example.org"));
        assertThat(cookie.path(), is("/static"));
        assertThat(cookie.isSecure(), is(true));
        assertThat(cookie.isHttpOnly(), is(true));
    }


    @Test
    public void providesUrlEncoding() {
        Cookie blah = newCookie().withName("Blah").withUrlEncodedValue("a / umm... yeah?").build();
        assertThat(blah.value(), is("a%20%2F%20umm...%20yeah%3F"));
    }
}