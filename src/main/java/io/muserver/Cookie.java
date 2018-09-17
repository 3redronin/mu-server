package io.muserver;

import io.netty.handler.codec.http.cookie.DefaultCookie;

import java.util.Set;
import java.util.stream.Collectors;


/**
 * A cookie
 */
public class Cookie {
    final DefaultCookie nettyCookie;

    /**
     * Creates a new cookie with secure settings such as HttpOnly and Secure set to true.
     * @param name The name of the cookie
     * @param value The value of the cookie
     * @return Returns a new cookie that can be sent to the response
     */
    public static Cookie secureCookie(String name, String value) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        return cookie;
    }
    public Cookie(String name, String value) {
        nettyCookie = new DefaultCookie(name, value);
    }

    public String name() {
        return nettyCookie.name();
    }

    public String value() {
        return nettyCookie.value();
    }

    public void setValue(String value) {
        nettyCookie.setValue(value);
    }

    public String domain() {
        return nettyCookie.domain();
    }

    public void setDomain(String domain) {
        nettyCookie.setDomain(domain);
    }

    public String path() {
        return nettyCookie.path();
    }

    public void setPath(String path) {
        nettyCookie.setPath(path);
    }

    public long maxAge() {
        return nettyCookie.maxAge();
    }

    public void setMaxAge(long maxAge) {
        nettyCookie.setMaxAge(maxAge);
    }

    public boolean isSecure() {
        return nettyCookie.isSecure();
    }

    public void setSecure(boolean secure) {
        nettyCookie.setSecure(secure);
    }

    public boolean isHttpOnly() {
        return nettyCookie.isHttpOnly();
    }

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

    static Set<Cookie> nettyToMu(Set<io.netty.handler.codec.http.cookie.Cookie> originals) {
        return originals.stream().map(n -> new Cookie(n.name(), n.value())).collect(Collectors.toSet());
    }
}
