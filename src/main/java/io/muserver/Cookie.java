package io.muserver;

import io.netty.handler.codec.http.cookie.DefaultCookie;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * A cookie that is sent from the server to the client.
 * <p>To create cookies, you can create a builder by calling {@link #builder()}.</p>
 */
public class Cookie {

    final DefaultCookie nettyCookie;

    /**
     * <p>Creates a new cookie with secure settings such as HttpOnly and Secure set to true.</p>
     * @param name The name of the cookie
     * @param value The value of the cookie
     * @return Returns a new cookie that can be sent to the response
     * @deprecated Please use {@link CookieBuilder#newSecureCookie()} instead
     */
    @Deprecated
    public static Cookie secureCookie(String name, String value) {
        return CookieBuilder.newSecureCookie()
            .withName(name)
            .withValue(value)
            .build();
    }

    /**
     * <p>Creates a new cookie with secure settings such as HttpOnly and Secure set to true.</p>
     * @param name The name of the cookie
     * @param value The value of the cookie
     * @deprecated Please use {@link CookieBuilder#newCookie()} instead
     */
    @Deprecated
    public Cookie(String name, String value) {
        nettyCookie = new DefaultCookie(name, value);
    }

    /**
     * @return The cookie name
     */
    public String name() {
        return nettyCookie.name();
    }

    /**
     * @return The cookie value
     */
    public String value() {
        return nettyCookie.value();
    }

    /**
     * Sets the value of the cookie.
     * @param value The value to set.
     * @deprecated Please create cookies with the {@link CookieBuilder}
     */
    @Deprecated
    public void setValue(String value) {
        nettyCookie.setValue(value);
    }

    /**
     * @return The domain this cookie is valid for
     */
    public String domain() {
        return nettyCookie.domain();
    }

    /**
     *
     * @param domain domain
     * @deprecated Please create cookies with the {@link CookieBuilder}
     */
    @Deprecated
    public void setDomain(String domain) {
        nettyCookie.setDomain(domain);
    }

    /**
     * @return The path this cookie applies to
     */
    public String path() {
        return nettyCookie.path();
    }

    /**
     *
     * @param path path
     * @deprecated Please create cookies with the {@link CookieBuilder}
     */
    @Deprecated
    public void setPath(String path) {
        nettyCookie.setPath(path);
    }

    /**
     * @return The max age in seconds of this cookie
     */
    public long maxAge() {
        return nettyCookie.maxAge();
    }

    /**
     * @return The SameSite value of the cookie, for example "Strict", "Lax", or "None"
     */
    public String sameSite() {
        return nettyCookie.sameSite().name();
    }

    /**
     *
     * @param maxAgeInSeconds max
     * @deprecated Please create cookies with the {@link CookieBuilder}
     */
    @Deprecated
    public void setMaxAge(long maxAgeInSeconds) {
        nettyCookie.setMaxAge(maxAgeInSeconds);
    }

    /**
     * @return True if this cookie is only readable over https
     */
    public boolean isSecure() {
        return nettyCookie.isSecure();
    }

    /**
     *
     * @param secure secure
     * @deprecated Please create cookies with the {@link CookieBuilder}
     */
    @Deprecated
    public void setSecure(boolean secure) {
        nettyCookie.setSecure(secure);
    }

    /**
     * @return True if this cookie is only available via HTTP. If false, client side script will not be able to read the cookie (if the client supports this).
     */
    public boolean isHttpOnly() {
        return nettyCookie.isHttpOnly();
    }

    /**
     *
     * @param httpOnly httpOnly
     * @deprecated Please create cookies with the {@link CookieBuilder}
     */
    @Deprecated
    public void setHttpOnly(boolean httpOnly) {
        nettyCookie.setHttpOnly(httpOnly);
    }

    public int hashCode() {
        return nettyCookie.hashCode();
    }

    public boolean equals(Object o) {
        return (this == o) || ((o instanceof Cookie) && nettyCookie.equals(o));
    }

    public String toString() {
        return nettyCookie.toString();
    }

    static List<Cookie> nettyToMu(Set<io.netty.handler.codec.http.cookie.Cookie> originals) {
        return originals.stream().map(n -> new Cookie(n.name(), n.value())).collect(Collectors.toList());
    }

    /**
     * Creates a new cookie builder with secure, Strict SameSite and httpOnly selected.
     * @return A new builder
     */
    public static CookieBuilder builder() {
        return CookieBuilder.newSecureCookie();
    }
}
