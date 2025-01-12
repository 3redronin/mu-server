package io.muserver;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.*;

import static java.util.Collections.emptyList;

/**
 * A builder to create cookies that will be sent on a Response with {@link MuResponse#addCookie(Cookie)}
 */
@NullMarked
public class CookieBuilder {

    private @Nullable String name;
    private @Nullable String value;
    private @Nullable String domain;
    private @Nullable String path;
    private @Nullable Long maxAge = null;
    private boolean secure;
    private boolean httpOnly;
    private Cookie.@Nullable SameSite sameSite;

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
     *              double quotes, comma, semicolon, and backslash. This can be an empty string, but not null.
     * @return This builder
     * @throws IllegalArgumentException If the value contains illegal characters or is null
     */
    public CookieBuilder withValue(String value) {
        Mutils.notNull("value", value);

        boolean matches = value.matches("^[\\x20-\\x7E]*|%(?:[0-9A-Fa-f]{2})$");
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
    public CookieBuilder withDomain(@Nullable String domain) {
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
    public CookieBuilder withPath(@Nullable String path) {
        if (path != null && path.isEmpty()) path = null;

        if (path != null) {
            boolean matches = value.matches("^[\\x20-\\x7E&&[^;]]+$");
            if (!matches) {
                throw new IllegalArgumentException("The path parameter can only include ASCII characters (excluding ';' and control characters).");
            }
        }

        this.path = path;
        return this;
    }

    /**
     * <p>Specifies how long the cookie will last for.</p>
     * <p>A value of 0 will cause most browsers to delete the cookie immediately.</p>
     * <p>Use {@link #makeSessionCookie()} to make this a session cookie that will cause most browsers to delete
     * the cookie when they close their browser.</p>
     *
     * @param maxAge The age to live in seconds, or <code>null</code> to make it a session cookie
     * @return This builder
     */
    public CookieBuilder withMaxAgeInSeconds(@Nullable Long maxAge) {
        this.maxAge = maxAge;
        return this;
    }

    /**
     * <p>Makes this cookie expire when the browser session expires (which may be when the user closes their
     * browser).</p>
     * <p>This overwrites any value set with {@link #withMaxAgeInSeconds(Long)}</p>
     *
     * @return This builder
     */
    public CookieBuilder makeSessionCookie() {
        return withMaxAgeInSeconds(null);
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
     * @throws IllegalArgumentException if the value is not a valid SameSite value
     */
    public CookieBuilder withSameSite(String sameSiteValue) {
        try {
            Cookie.SameSite sameSite = Cookie.SameSite.valueOf(sameSiteValue.toUpperCase());
            return withSameSite(sameSite);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid SameSite value. It should be one of: Lax, Strict, None");
        }
    }

    /**
     * Sets the <code>SameSite</code> property of the cookie. If not set, then <code>None</code> is used.
     * @param sameSite One of <code>Strict</code>, <code>Lax</code> or <code>None</code>
     * @return This builder
     */
    public CookieBuilder withSameSite(Cookie.SameSite sameSite) {
        this.sameSite = sameSite;
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
        if (value == null) throw new IllegalStateException("A cookie value cannot be null");
        return new Cookie(name, value, domain, path, maxAge, sameSite, secure, httpOnly);
    }

    /**
     * Converts a string used in the <code>cookie</code> request header into a list of <code>CookieBuilder</code>s.
     * @param input A header value, such as <code>name1=value1; name2=value2</code>
     * @return A cookie builder
     * @throws IllegalArgumentException if the value is not a valid cookie string
     */
    public static List<CookieBuilder> fromCookieHeader(@Nullable String input) {
        if (input == null || input.trim().isEmpty()) {
            return emptyList();
        }
        StringBuilder buffer = new StringBuilder();

        List<CookieBuilder> results = new ArrayList<>();

        int i = 0;
        while (i < input.length()) {

            String name = null;
            String value = null;
            State state = State.NAME;
            boolean isQuotedString = false;

            headerValueLoop:
            for (; i < input.length(); i++) {
                char c = input.charAt(i);

                if (state == State.NAME) {
                    if (c == '=') {
                        name = buffer.toString().trim();
                        buffer.setLength(0);
                        state = State.VALUE;
                    } else if (c == ';') {
                        i++;
                        break headerValueLoop;
                    } else if (ParseUtils.isVChar(c) || ParseUtils.isOWS(c)) {
                        buffer.append(c);
                    } else {
                        throw new IllegalArgumentException("Got ascii " + ((int) c) + " while in " + state + " at position " + i);
                    }
                } else if (state == State.VALUE) {

                    boolean isFirst = !isQuotedString && buffer.length() == 0;
                    if (isFirst && ParseUtils.isOWS(c)) {
                        // ignore it
                    } else if (isFirst && c == '"') {
                        isQuotedString = true;
                    } else {

                        if (isQuotedString) {
                            char lastChar = input.charAt(i - 1);
                            if (c == '\\' && lastChar != '\\') {
                                // don't append
                            } else if (c == '"') {
                                // this is the end, but we'll update on the next go
                                isQuotedString = false;
                            } else if (isCookieOctet(c)) {
                                buffer.append(c);
                            } else {
                                throw new IllegalArgumentException("Got ascii " + ((int) c) + " while in " + state + " at position " + i);
                            }
                        } else {
                            if (ParseUtils.isOWS(c)) {
                                // ignore it
                            } else if (c == ';') {
                                i++;
                                break headerValueLoop;
                            } else if (isCookieOctet(c)) {
                                buffer.append(c);
                            } else {
                                throw new IllegalArgumentException("Got character code " + ((int) c) + " (" + c + ") while parsing parameter value");
                            }
                        }
                    }
                }
            }
            switch (state) {
                case NAME:
                    name = buffer.toString().trim();
                    buffer.setLength(0);
                    break;
                case VALUE:
                    value = buffer.toString().trim();
                    buffer.setLength(0);
                    break;
                default:
                    if (buffer.length() > 0) {
                        throw new IllegalArgumentException("Unexpected ending point at state " + state + " for " + input);
                    }
            }

            CookieBuilder builder = CookieBuilder.newCookie()
                .withName(name)
                .withValue(value);
            results.add(builder);
        }
        return results;
    }



    /**
     * Converts a string used in the <code>set-cookie</code> response header into a <code>CookieBuilder</code>.
     * @param input A header value, such as <code>name=value; Secure</code>
     * @return A cookie builder
     * @throws IllegalArgumentException if the value is not a valid set-cookie string
     */
    public static Optional<CookieBuilder> fromSetCookieHeader(@Nullable String input) {
        if (input == null || input.trim().isEmpty()) {
            return Optional.empty();
        }
        StringBuilder buffer = new StringBuilder();

        CookieBuilder builder = null;

        int i = 0;
        while (i < input.length()) {
            if (builder != null) {
                throw new IllegalArgumentException("Only one set-cookie value is allowed");
            }

            String name = null;
            String value = null;
            LinkedHashMap<String, String> parameters = null;
            State state = State.NAME;
            String paramName = null;
            boolean isQuotedString = false;

            headerValueLoop:
            for (; i < input.length(); i++) {
                char c = input.charAt(i);

                if (state == State.NAME) {
                    if (c == '=') {
                        name = buffer.toString().trim();
                        buffer.setLength(0);
                        state = State.VALUE;
                    } else if (ParseUtils.isVChar(c) || ParseUtils.isOWS(c)) {
                        buffer.append(c);
                    } else {
                        throw new IllegalArgumentException("Got ascii " + ((int) c) + " while in " + state + " at position " + i);
                    }
                } else if (state == State.VALUE) {

                    boolean isFirst = !isQuotedString && buffer.length() == 0;
                    if (isFirst && ParseUtils.isOWS(c)) {
                        // ignore it
                    } else if (isFirst && c == '"') {
                        isQuotedString = true;
                    } else {

                        if (isQuotedString) {
                            char lastChar = input.charAt(i - 1);
                            if (c == '\\' && lastChar != '\\') {
                                // don't append
                            } else if (c == '"') {
                                // this is the end, but we'll update on the next go
                                isQuotedString = false;
                            } else if (isCookieOctet(c)) {
                                buffer.append(c);
                            } else {
                                throw new IllegalArgumentException("Got ascii " + ((int) c) + " while in " + state + " at position " + i);
                            }
                        } else {
                            if (c == ';') {
                                value = buffer.toString().trim();
                                buffer.setLength(0);
                                state = State.PARAM_NAME;
                            } else if (ParseUtils.isOWS(c)) {
                                // ignore it
                            } else if (isCookieOctet(c)) {
                                buffer.append(c);
                            } else {
                                throw new IllegalArgumentException("Got character code " + ((int) c) + " (" + c + ") while parsing parameter value");
                            }
                        }
                    }

                } else if (state == State.PARAM_NAME) {
                    if (c == ';') {
                        // a semi-colon without a parameter, like "something;"
                        paramName = buffer.toString();
                        buffer.setLength(0);
                        i--; // replay this char with the name state which will add the param to the map with an empty string
                        state = State.PARAM_VALUE;
                    } else if (c == '=') {
                        paramName = buffer.toString();
                        buffer.setLength(0);
                        state = State.PARAM_VALUE;
                    } else if (ParseUtils.isTChar(c)) {
                        buffer.append(c);
                    } else if (ParseUtils.isOWS(c)) {
                        if (buffer.length() > 0) {
                            throw new IllegalArgumentException("Got whitespace in parameter name while in " + state + " - header was " + buffer);
                        }
                    } else {
                        throw new IllegalArgumentException("Got ascii " + ((int) c) + " while in " + state);
                    }
                } else {
                    boolean isFirst = !isQuotedString && buffer.length() == 0;
                    if (isFirst && ParseUtils.isOWS(c)) {
                        // ignore it
                    } else if (isFirst && c == '"') {
                        isQuotedString = true;
                    } else {

                        if (isQuotedString) {
                            char lastChar = input.charAt(i - 1);
                            if (c == '\\') {
                                // don't append
                            } else if (lastChar == '\\') {
                                buffer.append(c);
                            } else if (c == '"') {
                                // this is the end, but we'll update on the next go
                                isQuotedString = false;
                            } else {
                                buffer.append(c);
                            }
                        } else {
                            if (ParseUtils.isTChar(c) || c == '/') {
                                buffer.append(c);
                            } else if (c == ';') {
                                if (parameters == null) {
                                    parameters = new LinkedHashMap<>(); // keeps insertion order
                                }
                                parameters.put(paramName.toLowerCase(), buffer.toString());
                                buffer.setLength(0);
                                paramName = null;
                                state = State.PARAM_NAME;
                            } else if (ParseUtils.isOWS(c)) {
                                // ignore it
                            } else {
                                throw new IllegalArgumentException("Got character code " + ((int) c) + " (" + c + ") while parsing parameter value");
                            }
                        }
                    }
                }
            }
            switch (state) {
                case NAME:
                    name = buffer.toString().trim();
                    buffer.setLength(0);
                    break;
                case VALUE:
                    value = buffer.toString().trim();
                    buffer.setLength(0);
                    break;
                case PARAM_NAME:
                    paramName = buffer.toString().toLowerCase();
                    if (!paramName.isBlank()) {
                        if (parameters == null) {
                            parameters = new LinkedHashMap<>(); // keeps insertion order
                        }
                        parameters.put(paramName, "");
                        buffer.setLength(0);
                    }
                case PARAM_VALUE:
                    if (parameters == null) {
                        parameters = new LinkedHashMap<>(); // keeps insertion order
                    }
                    parameters.put(paramName.toLowerCase(), buffer.toString());
                    buffer.setLength(0);
                    break;
                default:
                    if (buffer.length() > 0) {
                        throw new IllegalArgumentException("Unexpected ending point at state " + state + " for " + input);
                    }
            }

            builder = CookieBuilder.newCookie()
                .withName(name)
                .withValue(value);
            if (parameters != null) {
                if (parameters.containsKey("domain")) builder.withDomain(parameters.get("domain"));
                if (parameters.containsKey("httponly")) builder.httpOnly(true);
                if (parameters.containsKey("max-age")) builder.withMaxAgeInSeconds(Long.parseLong(parameters.get("max-age")));
                if (parameters.containsKey("path")) builder.withPath(parameters.get("path"));
                if (parameters.containsKey("secure")) builder.secure(true);
                if (parameters.containsKey("samesite")) builder.withSameSite(parameters.get("samesite"));
            }
        }
        return Optional.ofNullable(builder);
    }


    private enum State {NAME, VALUE, PARAM_NAME, PARAM_VALUE}

    private static boolean isCookieOctet(char c) {
        // one of %x21 / %x23-2B / %x2D-3A / %x3C-5B / %x5D-7E
        return (c == 0x21) ||
            (c >= 0x23 && c <= 0x2B) ||
            (c >= 0x2D && c <= 0x3A) ||
            (c >= 0x3C && c <= 0x5B) ||
            (c >= 0x5D && c <= 0x7E);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CookieBuilder that = (CookieBuilder) o;
        return secure == that.secure && httpOnly == that.httpOnly && Objects.equals(name, that.name) && Objects.equals(value, that.value) && Objects.equals(domain, that.domain) && Objects.equals(path, that.path) && Objects.equals(maxAge, that.maxAge) && sameSite == that.sameSite;
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, value, domain, path, maxAge, secure, httpOnly, sameSite);
    }

    @Override
    public String toString() {
        return "CookieBuilder{" +
            "name='" + name + '\'' +
            ", value='" + value + '\'' +
            ", domain='" + domain + '\'' +
            ", path='" + path + '\'' +
            ", maxAge=" + maxAge +
            ", secure=" + secure +
            ", httpOnly=" + httpOnly +
            ", sameSite=" + sameSite +
            '}';
    }
}
