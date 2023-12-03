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
     */
    Cookie(String name, String value) {
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
     * @return The domain this cookie is valid for
     */
    public String domain() {
        return nettyCookie.domain();
    }

    /**
     * @return The path this cookie applies to
     */
    public String path() {
        return nettyCookie.path();
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
     * @return True if this cookie is only readable over https
     */
    public boolean isSecure() {
        return nettyCookie.isSecure();
    }

    /**
     * @return True if this cookie is only available via HTTP. If false, client side script will not be able to read the cookie (if the client supports this).
     */
    public boolean isHttpOnly() {
        return nettyCookie.isHttpOnly();
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
