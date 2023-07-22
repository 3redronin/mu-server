package io.muserver;

import io.netty.handler.codec.http.cookie.CookieHeaderNames;
import io.netty.handler.codec.http.cookie.DefaultCookie;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A builder to create cookies that will be sent on a Response with {@link MuResponse#addCookie(Cookie)}
 */
public class CookieBuilder {

    private String name;
    private String value;
    private String domain;
    private String path;
    private long maxAge = DefaultCookie.UNDEFINED_MAX_AGE;
    private boolean secure;
    private boolean httpOnly;
    private CookieHeaderNames.SameSite sameSite = CookieHeaderNames.SameSite.None;

    /**
     * Sets the name of the cookie.
     *
     * @param name The name of the cookie
     * @return This builder
     */
    public CookieBuilder withName(String name) {
        Mutils.notNull("name", name);
        boolean matches = name.matches("^[0-9A-Za-z!#$%&'*+\\-.^_`|~]+$");
        if (!matches) {
            throw new IllegalArgumentException("A cookie name can only be alphanumeric ASCII characters or any of \"!#$%&'*+-.^_`|~\" (excluding quotes)");
        }
        this.name = name;
        return this;
    }

    /**
     * <p>Sets the value of the cookie.</p>
     * <p>Note that only a subset of ASCII characters are allowed (any other characters must be encoded).
     * Consider using {@link #withUrlEncodedValue(String)} instead if you want to use arbitrary values.</p>
     *
     * @param value The value to use for the cookie, which can be any US-ASCII characters excluding CTLs, whitespace,
     *              double quotes, comma, semicolon, and backslash.
     * @return This builder
     * @throws IllegalArgumentException If the value contains illegal characters
     */
    public CookieBuilder withValue(String value) {
        Mutils.notNull("value", value);

        boolean matches = value.matches("^[0-9A-Za-z!#$%&'()*+\\-./:<=>?@\\[\\]^_`{|}~]*$");
        if (!matches) {
            throw new IllegalArgumentException("A cookie value can only be ASCII characters excluding control characters, whitespace, quotes, commas, semicolons and backslashes. Consider using CookieBuilder.withUrlEncodedValue instead.");
        }

        this.value = value;
        return this;
    }

    /**
     * <p>Sets the value of the cookie by URL Encoding the given value.</p>
     *
     * @param value A value containing any characters that will be URL Encoded.
     * @return This builder
     */
    public CookieBuilder withUrlEncodedValue(String value) {
        return withValue(Mutils.urlEncode(value));
    }

    /**
     * <p>Sets the host that clients should send the cookie over. By default it is the current domain (but not subdomains).</p>
     * <p>To allow sub-domains, specify the current domain (e.g. with <code>request.uri().getHost()</code>).</p>
     *
     * @param domain The host part of a URL (without scheme or port), e.g. <code>example.org</code>
     * @return This builder
     */
    public CookieBuilder withDomain(String domain) {
        if (domain != null && domain.contains(":")) {
            throw new IllegalArgumentException("The domain value should only be a host name (and should not include the scheme or the port)");
        }
        this.domain = domain;
        return this;
    }

    /**
     * <p>Sets the path prefix that the cookie will be sent with, or null to send to all paths.</p>
     *
     * @param path A path such as <code>/order</code> which would cause cookies to be sent with requests
     *             to paths such as <code>/order</code> and <code>/order/checkout</code> etc.
     * @return This builder
     */
    public CookieBuilder withPath(String path) {
        this.path = path;
        return this;
    }

    /**
     * <p>Specifies how long the cookie will last for.</p>
     * <p>A value of 0 will cause most browsers to delete the cookie immediately.</p>
     * <p>Use {@link #makeSessionCookie()} to make this a session cookie that will cause most browsers to delete
     * the cookie when they close their browser.</p>
     *
     * @param maxAge The age to live in seconds.
     * @return This builder
     */
    public CookieBuilder withMaxAgeInSeconds(long maxAge) {
        this.maxAge = maxAge;
        return this;
    }

    /**
     * <p>Makes this cookie expire when the browser session expires (which may be when the user closes their
     * browser).</p>
     * <p>This overwrites any value set with {@link #withMaxAgeInSeconds(long)}</p>
     *
     * @return This builder
     */
    public CookieBuilder makeSessionCookie() {
        return withMaxAgeInSeconds(DefaultCookie.UNDEFINED_MAX_AGE);
    }

    /**
     * <p>Instructs clients on whether or not cookies can be sent over non-secure (HTTP) connections.</p>
     * <p>It is strongly recommended to use this if your site is HTTPS only as it may prevent private information
     * being sent over HTTP.</p>
     *
     * @param secure <code>true</code> to make this cookie HTTPS-only, or <code>false</code> to allow HTTPS and HTTP.
     * @return This builder
     */
    public CookieBuilder secure(boolean secure) {
        this.secure = secure;
        return this;
    }

    /**
     * Instructs browsers on whether or not the cookie can be accessed via JavaScript. This is a secure feature
     * that should be used unless JavaScript access is required.
     *
     * @param httpOnly <code>true</code> to make it so JavaScript cannot access this cookie; otherwise <code>false</code>
     * @return This builder
     */
    public CookieBuilder httpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
        return this;
    }

    /**
     * Sets the <code>SameSite</code> property of the cookie. If not set, then <code>None</code> is used.
     * @param sameSiteValue One of <code>Strict</code>, <code>Lax</code> or <code>None</code>
     * @return This builder
     */
    public CookieBuilder withSameSite(String sameSiteValue) {
        try {
            this.sameSite = CookieHeaderNames.SameSite.valueOf(sameSiteValue);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid SameSite value. It should be one of: " +
                Stream.of(CookieHeaderNames.SameSite.values())
                    .map(CookieHeaderNames.SameSite::name)
                    .collect(Collectors.joining(", ")));
        }
        return this;
    }

    /**
     * Creates a new session cookie
     *
     * @return A builder to create a new cookie;
     */
    public static CookieBuilder newCookie() {
        return new CookieBuilder();
    }

    /**
     * Creates a new session cookie that is only sent over HTTPS and cannot be accessed with JavaScript
     * with a Strict samesite policy applied.
     *
     * @return A builder to create a new cookie;
     */
    public static CookieBuilder newSecureCookie() {
        return new CookieBuilder()
            .secure(true)
            .httpOnly(true)
            .withSameSite("Strict");
    }

    /**
     * @return Returns a newly created cookie.
     */
    public Cookie build() {
        if (Mutils.nullOrEmpty(name)) throw new IllegalStateException("A cookie name must be specified");
        if (value == null) throw new IllegalStateException("A cookie value must be specified");
        Cookie c = new Cookie(name, value);
        c.nettyCookie.setDomain(domain);
        c.nettyCookie.setPath(path);
        c.nettyCookie.setMaxAge(maxAge);
        c.nettyCookie.setSecure(secure);
        c.nettyCookie.setHttpOnly(httpOnly);
        c.nettyCookie.setSameSite(sameSite);
        return c;
    }
}
