package io.muserver;


import java.util.Objects;

/**
 * A cookie that is sent from the server to the client.
 * <p>To create cookies, you can create a builder by calling {@link #builder()}.</p>
 */
public class Cookie {

    private final String name;
    private final String value;
    private final String domain;
    private final String path;
    private final Long maxAge;
    private final SameSite sameSite;
    private final boolean isSecure;
    private final boolean isHttpOnly;

    public Cookie(String name, String value, String domain, String path, Long maxAge, SameSite sameSite, boolean isSecure, boolean isHttpOnly) {
        this.name = name;
        this.value = value;
        this.domain = domain;
        this.path = path;
        this.maxAge = maxAge;
        this.sameSite = sameSite;
        this.isSecure = isSecure;
        this.isHttpOnly = isHttpOnly;
    }


    /**
     * @return The cookie name
     */
    public String name() {
        return name;
    }

    /**
     * @return The cookie value
     */
    public String value() {
        return value;
    }

    /**
     * @return The domain this cookie is valid for
     */
    public String domain() {
        return domain;
    }

    /**
     * @return The path this cookie applies to
     */
    public String path() {
        return path;
    }

    /**
     * @return The max age in seconds of this cookie
     */
    public Long maxAge() {
        return maxAge;
    }

    /**
     * @return The SameSite value of the cookie, for example "Strict", "Lax", or "None"
     */
    public SameSite sameSite() {
        return sameSite;
    }

    /**
     * @return True if this cookie is only readable over https
     */
    public boolean isSecure() {
        return isSecure;
    }

    /**
     * @return True if this cookie is only available via HTTP. If false, client side script will not be able to read the cookie (if the client supports this).
     */
    public boolean isHttpOnly() {
        return isHttpOnly;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Cookie cookie = (Cookie) o;
        return isSecure == cookie.isSecure && isHttpOnly == cookie.isHttpOnly && Objects.equals(name, cookie.name) && Objects.equals(value, cookie.value) && Objects.equals(domain, cookie.domain) && Objects.equals(path, cookie.path) && Objects.equals(maxAge, cookie.maxAge) && Objects.equals(sameSite, cookie.sameSite);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value, domain, path, maxAge, sameSite, isSecure, isHttpOnly);
    }

    /**
     * Creates a new cookie builder with secure, Strict SameSite and httpOnly selected.
     * @return A new builder
     */
    public static CookieBuilder builder() {
        return CookieBuilder.newSecureCookie();
    }

    @Override
    public String toString() {
        var sb = new StringBuilder();
        sb.append(name).append('=');
        if (value != null) sb.append(ParseUtils.quoteIfNeeded(value));
        if (domain != null) sb.append("; Domain=").append(domain);
        if (path != null) sb.append("; Path=").append(path);
        if (maxAge != null) sb.append("; Max-Age=").append(maxAge.longValue());
        if (sameSite != null) sb.append("; SameSite=").append(sameSite.cookieValue());
        if (isSecure) sb.append("; Secure");
        if (isHttpOnly) sb.append("; HttpOnly");
        return sb.toString();
    }

    /**
     * A value of a <code>SameSite</code> cookie attribute.
     */
    public enum SameSite {

        /**
         * The cookie will be sent with both cross-site and same-site requests.
         * <p>Note that in this case the cookie should be marked as <code>secure</code>.</p>
         */
        NONE("None"),
        /**
         * The cookie will only be sent if the request originated from the same domain.
         * <p>Note that if navigating from one site to another then the request will be considered cross-site.
         * For example, if the user clicks on a link from <code>https://app1.example.org</code> which links to
         * <code>https://app2.example.org</code> then the cookies for <code>app2</code> will not be sent in strict
         * mode. To allow for this top-level navigation case, use {@link #LAX}.
         * </p>
         */
        STRICT("Strict"),
        /**
         * The cookie will only be sent if the request originated from the same domain, or when a top-level browser navigation
         * to a site occurs.
         * <p>Clients may use this as the default if no value is set.</p>
         */
        LAX("Lax");

        private final String cookieValue;

        SameSite(String cookieValue) {
            this.cookieValue = cookieValue;
        }

        /**
         * @return The attribute value as used in the <code>set-cookie</code> header.
         */
        public String cookieValue() {
            return this.cookieValue;
        }
    }

}
