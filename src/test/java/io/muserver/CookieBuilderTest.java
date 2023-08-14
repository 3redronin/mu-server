package io.muserver;

import org.junit.Assert;
import org.junit.Test;

import static io.muserver.CookieBuilder.newCookie;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class CookieBuilderTest {

    @Test
    public void secureBuilderDefaultsToSecureHttpOnlyStrictSameSite() {
        Cookie cookie = CookieBuilder.newSecureCookie().withName("dummy").withValue("wummy").build();
        assertThat(cookie.isSecure(), is(true));
        assertThat(cookie.isHttpOnly(), is(true));
        assertThat(cookie.sameSite(), is(Cookie.SameSite.STRICT));
    }

    @Test
    public void sameSiteDefaultsToNull() {
        Cookie cookie = CookieBuilder.newCookie().withName("dummy").withValue("wummy").build();
        assertThat(cookie.isSecure(), is(false));
        assertThat(cookie.isHttpOnly(), is(false));
        assertThat(cookie.sameSite(), is(nullValue()));
    }

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
    public void throwsWithInvalidSameSite() {
        try {
            newCookie().withSameSite("something-invalid");
            Assert.fail("Should throw");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), is("Invalid SameSite value. It should be one of: Lax, Strict, None"));
        }
    }

    @Test
    public void throwsWithNoValue() {
        try {
            newCookie().withName("cookiename").build();
            Assert.fail("Should throw");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), is("A cookie value cannot be null"));
        }
    }

    @Test
    public void invalidValuesAreRejected() {
        try {
            newCookie().withValue("你好");
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
            .withMaxAgeInSeconds(20L)
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


    private final String allowedCookieNameChars = "!#$%&'*+-.0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz|^~_";
    private final String allowedCookieValueChars = "!#$%&'()*+-./0123456789:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz|^~`";

    @Test
    public void itCanParseThings() {
        for (char allowedCookieNameChar : allowedCookieNameChars.toCharArray()) {
            if (!Parser.isTChar(allowedCookieNameChar)) {
                System.out.println("not allowedCookieNameChar = " + allowedCookieNameChar);
            }
        }
        assertThat(CookieBuilder.fromString("sessionId=38afes7a8"), contains(cook("sessionId", "38afes7a8")));
        assertThat(CookieBuilder.fromString("sessionId="), contains(cook("sessionId", "")));
        assertThat(CookieBuilder.fromString("__Host-example=34d8g; SameSite=None; Secure; Path=/; Partitioned;"), contains(cook("__Host-example", "34d8g").secure(true).withSameSite(Cookie.SameSite.NONE).withPath("/")));
        assertThat(CookieBuilder.fromString(allowedCookieNameChars + "=" + allowedCookieValueChars), contains(cook(allowedCookieNameChars, allowedCookieValueChars)));
    }

    @Test
    public void itCanParseMultipleValues() {
        assertThat(CookieBuilder.fromString("cookie1=value1, cookie2=value2"), contains(cook("cookie1", "value1"), cook("cookie2", "value2")));
        assertThat(CookieBuilder.fromString(" cookie1=value1,cookie2=value2 "), contains(cook("cookie1", "value1"), cook("cookie2", "value2")));
        assertThat(CookieBuilder.fromString(" cookie1=value1;httponly,cookie2=value2 "), contains(cook("cookie1", "value1").httpOnly(true), cook("cookie2", "value2")));
    }

    @Test
    public void valuesCanBeQuotedStrings() {
        assertThat(CookieBuilder.fromString(
            "cookie1=\"value_1\";secure, " +
                "cookie2=\"value+-/_2\""),
            contains(
                cook("cookie1", "value_1").secure(true),
                cook("cookie2", "value+-/_2")
            ));

    }

    private static CookieBuilder cook(String name, String value) {
        return newCookie().withName(name).withValue(value);
    }

}