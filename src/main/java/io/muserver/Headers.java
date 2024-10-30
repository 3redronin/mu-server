package io.muserver;

import io.muserver.rest.MuRuntimeDelegate;
import jakarta.ws.rs.core.MediaType;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * HTTP headers
 */
public interface Headers extends Iterable<Map.Entry<String, String>> {
    /**
     * <p>Gets the value with the given name, or null if there is no parameter with that name.</p>
     * <p>If there are multiple parameters with the same name, the first one is returned.</p>
     *
     * @param name The name of the parameter to get
     * @return The value, or null
     */
    default String get(String name) {
        return get((CharSequence) name);
    }

    /**
     * <p>Gets the value with the given name, or null if there is no parameter with that name.</p>
     * <p>If there are multiple parameters with the same name, the first one is returned.</p>
     *
     * @param name The name of the parameter to get
     * @return The value, or null
     */
    String get(CharSequence name);

    /**
     * <p>Gets the value with the given name, or the default value if there is no parameter with that name.</p>
     * <p>If there are multiple parameters with the same name, the first one is returned.</p>
     *
     * @param name         The name of the parameter to get
     * @param defaultValue The default value to use if there is no given value
     * @return The value of the parameter, or the default value
     */
    default String get(CharSequence name, String defaultValue) {
        String v = get(name);
        return v == null ? defaultValue : v;
    }

    /**
     * Gets the parameter as an integer, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as an integer.
     */
    default int getInt(CharSequence name, int defaultValue) {
        String v = get(name);
        if (v == null) return defaultValue;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Gets the parameter as a long, or returns the default value if it was not specified or was in an invalid format.
     *
     * @param name         The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as a long.
     */
    default long getLong(String name, long defaultValue) {
        String v = get(name);
        if (v == null) return defaultValue;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Gets the parameter as a float, or returns the default value if it was not specified or was in an invalid format.
     *
     * @param name         The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as a float.
     */
    default float getFloat(String name, float defaultValue) {
        String v = get(name);
        if (v == null) return defaultValue;
        try {
            return Float.parseFloat(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Gets the parameter as a double, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as a double.
     */
    default double getDouble(String name, double defaultValue) {
        String v = get(name);
        if (v == null) return defaultValue;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * <p>Gets a parameter as a boolean, where values such as <code>true</code>, <code>on</code> and <code>yes</code> as
     * considered true, and other values (or no parameter with the name) is considered false.</p>
     * <p>This can be used to access checkbox values as booleans.</p>
     *
     * @param name The name of the parameter.
     * @return Returns true if the value was truthy, or false if it was falsy or not specified.
     */
    default boolean getBoolean(String name) {
        String v = get(name);
        if (v == null) return false;
        return Mutils.isTruthy(v);
    }

    /**
     * Gets a date header.
     * <p>This converts the string date values into milliseconds</p>
     *
     * @param name The header name
     * @return The value in milliseconds of the date header, or null if not found
     */
    default Long getTimeMillis(CharSequence name) {
        var s = get(name);
        if (s == null) return null;
        return Mutils.fromHttpDate(s).getTime();
    }

    /**
     * Gets a date header.
     * <p>This converts the string date values into milliseconds</p>
     *
     * @param name         The header name
     * @param defaultValue The default to use if no date header is available
     * @return The value in milliseconds of the date header, or the default if not found
     */
    default long getTimeMillis(CharSequence name, long defaultValue) {
        var s = get(name);
        if (s == null) return defaultValue;
        return Mutils.fromHttpDate(s).getTime();
    }

    /**
     * Gets all the parameters with the given name, or an empty list if none are found.
     *
     * @param name The parameter name to get
     * @return All values of the parameter with the given name
     */
    default List<String> getAll(String name) {
        return getAll((CharSequence) name);
    }

    /**
     * Gets all the parameters with the given name, or an empty list if none are found.
     *
     * @param name The parameter name to get
     * @return All values of the parameter with the given name
     */
    List<String> getAll(CharSequence name);

    /**
     * @return All the headers
     */
    List<Map.Entry<String, String>> entries();

    /**
     * Returns true if the given parameter is specified with any value
     *
     * @param name The name of the value
     * @return True if it's specified; otherwise false.
     */
    default boolean contains(String name) {
        return contains((CharSequence) name);
    }

    /**
     * Returns true if the given parameter is specified with any value
     * @param name The name of the value
     * @return True if it's specified; otherwise false.
     */
    boolean contains(CharSequence name);

    /**
     * @return An iterator to iterate through the headers
     */
    @NotNull Iterator<Map.Entry<String, String>> iterator();

    /**
     * @return True if there are no headers
     */
    boolean isEmpty();

    /**
     * @return The number of headers. Repeated headers are counted twice.
     */
    int size();

    /**
     * @return The header names in these headers
     */
    Set<String> names();

    /**
     * Adds an item to these headers. If a header with the given name already exists then this value is added
     * rather than replaced.
     *
     * @param name  The header name
     * @param value The value
     * @return This headers object
     */
    default Headers add(String name, Object value) {
        return add((CharSequence) name, value);
    }

    /**
     * Adds an item to these headers. If a header with the given name already exists then this value is added
     * rather than replaced.
     * @param name The header name
     * @param value The value
     * @return This headers object
     */
    Headers add(CharSequence name, Object value);

    /**
     * Adds an item to these headers. If a header with the given name already exists then this value is added
     * rather than replaced.
     *
     * @param name   The header name
     * @param values The values
     * @return This headers object
     */
    default Headers add(String name, Iterable<?> values) {
        return add((CharSequence) name, values);
    }

    /**
     * Adds an item to these headers. If a header with the given name already exists then this value is added
     * rather than replaced.
     * @param name The header name
     * @param values The value
     * @return This headers object
     */
    Headers add(CharSequence name, Iterable<?> values);

    /**
     * Adds all headers from another headers object to this
     * @param headers The headers to add
     * @return This headers object
     */
    Headers add(Headers headers);

    /**
     * Adds an integer value
     *
     * @param name  The header name
     * @param value The value
     * @return This headers object
     */
    default Headers addInt(CharSequence name, int value) {
        return add(name, String.valueOf(value));
    }

    /**
     * Sets a header value. If a header with the given name already exists then this replaces it.
     *
     * @param name  The header name
     * @param value The value
     * @return This headers object
     */
    default Headers set(String name, Object value) {
        return set((CharSequence) name, value);
    }

    /**
     * Sets a header value. If a header with the given name already exists then this replaces it.
     * @param name The header name
     * @param value The value
     * @return This headers object
     */
    Headers set(CharSequence name, Object value);

    /**
     * Sets a header value list. If a header with the given name already exists then this replaces it.
     *
     * @param name   The header name
     * @param values The value
     * @return This headers object
     */
    default Headers set(String name, Iterable<?> values) {
        return set((CharSequence) name, values);
    }

    /**
     * Sets a header value list. If a header with the given name already exists then this replaces it.
     * @param name The header name
     * @param values The value
     * @return This headers object
     */
    Headers set(CharSequence name, Iterable<?> values);

    /**
     * Removes all the current headers and adds them from the given headers.
     * @param headers The headers to use
     * @return This headers object
     */
    Headers set(Headers headers);

    /**
     * Sets all the values from the given headers object, overwriting any existing headers with the same names.
     * @param headers The headers to use
     * @return This headers object
     */
    Headers setAll(Headers headers);

    /**
     * Sets the given integer value, replacing the existing value if it is already set.
     *
     * @param name  The header name
     * @param value The value to set
     * @return This headers object
     */
    default Headers setInt(CharSequence name, int value) {
        return set(name, value);
    }

    /**
     * Removes a header. Does nothing if the header is not set.
     *
     * @param name The header to remove
     * @return This headers object
     */
    default Headers remove(String name) {
        return remove((CharSequence) name);
    }

    /**
     * Removes a header. Does nothing if the header is not set.
     * @param name The header to remove
     * @return This headers object
     */
    Headers remove(CharSequence name);

    /**
     * @return Removes all the headers from this object
     */
    Headers clear();

    /**
     * Checks if a header with the given name exists with the given value.
     *
     * @param name       The case-insensitive header name to check.
     * @param value      The value to check for.
     * @param ignoreCase If true, the case of the value is ignored.
     * @return True if a header name with the given value exists.
     */
    default boolean contains(String name, String value, boolean ignoreCase) {
        return contains((CharSequence) name, value, ignoreCase);
    }

    /**
     * Checks if a header with the given name exists with the given value.
     * @param name The case-insensitive header name to check.
     * @param value The value to check for.
     * @param ignoreCase If true, the case of the value is ignored.
     * @return True if a header name with the given value exists.
     */
    boolean contains(CharSequence name, CharSequence value, boolean ignoreCase);

    /**
     * Similar to {@link #contains(String, String, boolean)} but returns true even if a value occurs in a comma-separated
     * header list.
     * @param name The case-insensitive header name to check.
     * @param value The value to check for.
     * @param ignoreCase If true, the case of the value is ignored.
     * @return True if a header name with the given value exists.
     */
    boolean containsValue(CharSequence name, CharSequence value, boolean ignoreCase);

    /**
     * Returns true if the headers suggest there is a message body by checking if there is a {@link HeaderNames#TRANSFER_ENCODING}
     * header or the {@link HeaderNames#CONTENT_LENGTH} is greater than 0.
     *
     * @return True if there should be a body; otherwise false;
     */
    default boolean hasBody() {
        var cl = getLong("content-length", -1);
        if (cl > 0) return true;
        if (cl == 0) return false;
        return containsValue(HeaderNames.TRANSFER_ENCODING, HeaderValues.CHUNKED, false);
    }

    /**
     * <p>Gets the <code>Accept-Charset</code> header value.</p>
     * <p>For example, if a client sends <code>text/html,application/xml;q=0.9</code>
     * then this would return a list of two values: text/html, and application/xml where application/xml
     * has a parameter <code>q</code> of value <code>0.9</code></p>
     *
     * @return Returns a parsed <code>Accept</code> header, or an empty list if none specified.
     */
    default List<ParameterizedHeaderWithValue> accept() {
        return ParameterizedHeaderWithValue.fromString(get(HeaderNames.ACCEPT));
    }

    /**
     * <p>Gets the <code>Accept</code> header value.</p>
     * <p>For example, if a client sends <code>iso-8859-5, unicode-1-1;q=0.8</code>
     * then this would return a list of two values: iso-8859-5, and unicode-1-1 where unicode-1-1
     * has a parameter <code>q</code> of value <code>0.8</code></p>
     *
     * @return Returns a parsed <code>Accept-Charset</code> header, or an empty list if none specified.
     */
    default List<ParameterizedHeaderWithValue> acceptCharset() {
        return ParameterizedHeaderWithValue.fromString(get(HeaderNames.ACCEPT_CHARSET));
    }

    /**
     * <p>Gets the <code>Accept-Encoding</code> header value.</p>
     * <p>For example, if a client sends <code>gzip, deflate</code>
     * then this would return a list of two values: gzip and deflate</p>
     *
     * @return Returns a parsed <code>Accept-Encoding</code> header, or an empty list if none specified.
     */
    default List<ParameterizedHeaderWithValue> acceptEncoding() {
        return ParameterizedHeaderWithValue.fromString(get(HeaderNames.ACCEPT_ENCODING));
    }

    /**
     * <p>Gets the <code>Forwarded</code> header value.</p>
     * <p>For example, if a client sends <code>for=192.0.2.60;proto=http;by=203.0.113.43</code>
     * then this would return a list of length one that has for, proto, and by values.</p>
     * <p>If there is no Forwarded header, however there are X-Forwarded-* headers, then those
     * will be used to generate pseudo-forwarded headers.</p>
     *
     * @return Returns a parsed <code>Forwarded</code> header, or an empty list if none specified.
     */
    default List<ForwardedHeader> forwarded() {
        return Headtils.getForwardedHeaders(this);
    }

    /**
     * <p>Gets the <code>Accept-Language</code> header value.</p>
     * <p>For example, if a client sends <code>en-US,en;q=0.5</code>
     * then this would return a list of two values: en-US and en where en has a <code>q</code> value of <code>0.5</code></p>
     *
     * @return Returns a parsed <code>Accept-Language</code> header, or an empty list if none specified.
     */
    default List<ParameterizedHeaderWithValue> acceptLanguage() {
        return ParameterizedHeaderWithValue.fromString(get(HeaderNames.ACCEPT_LANGUAGE));
    }

    /**
     * Gets the <code>Cache-Control</code> header value.
     *
     * @return A map of cache control directives to their optional values. If no cache-control
     * is in the header, then the resulting map will be empty.
     */
    default ParameterizedHeader cacheControl() {
        return ParameterizedHeader.fromString(get(HeaderNames.CACHE_CONTROL));
    }

    /**
     * Gets the parsed <code>Content-Type</code> header value.
     *
     * @return The media type of the content specified by the headers, or <code>null</code> if not set.
     */
    default MediaType contentType() {
        MuRuntimeDelegate.ensureSet();
        var mt = get(HeaderNames.CONTENT_TYPE);
        if (mt == null) return null;
        return MediaType.valueOf(mt);
    }

    /**
     * Returns the parsed <code>Connection</code> header value.
     * <p>Duplicate values are removed.</p>
     * <p>Where there are multiple connection fields they are combined</p>
     *
     * @return a list of connection tokens.
     */
    default TokenListHeader connection() {
        return TokenListHeader.parse(getAll(HeaderNames.CONNECTION), false);
    }

    /**
     * Returns the parsed <code>Vary</code> header value.
     * <p>Duplicate values are removed.</p>
     * <p>Where there are multiple vary fields they are combined</p>
     *
     * @return a list of vary tokens.
     */
    default TokenListHeader vary() {
        return TokenListHeader.parse(getAll(HeaderNames.VARY), false);
    }

    /**
     * Gets the parsed <code>cookie</code> value.
     * <p>This is only relevant for request cookies</p>
     * @return The cookies sent on a request
     */
    default List<Cookie> cookies() {
        var list = new ArrayList<Cookie>();
        for (String headerString : getAll(HeaderNames.COOKIE)) {
            for (CookieBuilder cookieBuilder : CookieBuilder.fromCookieHeader(headerString)) {
                list.add(cookieBuilder.build());
            }
        }
        return list;
    }

    /**
     * Tests whether the connection should be closed based on the current header values and HTTP version.
     * @param httpVersion The HTTP version
     * @return <code>true</code> if the underlying TCP connection should be closed at the conclusion of the request and response.
     */
    default boolean closeConnectionRequested(HttpVersion httpVersion) {
        switch (httpVersion) {
            case HTTP_2:
                return false;
            case HTTP_1_1:
                return containsValue(HeaderNames.CONNECTION, HeaderValues.CLOSE.toString(), true);
            case HTTP_1_0:
                return !connection().contains(HeaderValues.KEEP_ALIVE.toString(), true);
        }
        throw new IllegalArgumentException("Invalid version: " + httpVersion);
    }

    /**
     * Returns a string representation of the headers.
     * <p><strong>Note:</strong> The following headers will have their actual values replaced with the string <code>(hidden)</code>
     * in order to protect potentially sensitive information: <code>authorization</code>, <code>cookie</code> and <code>set-cookie</code>.</p>
     * <p>If you wish to print all values or customize the header values that are hidden, use {@link #toString(Collection)}</p>
     *
     * @return a string representation of these headers
     */
    String toString();

    /**
     * Returns a string representation of the headers with selected header values replaced with the string <code>(hidden)</code>.
     * <p>This may be useful where headers are logged for diagnostic purposes while not revealing values that are held in
     * potentially sensitive headers.</p>
     *
     * @param toSuppress A collection of case-insensitive header names which will not have their values printed.
     *                   Pass an empty collection to print all header values. A <code>null</code> value will hide
     *                   the header values as defined on {@link #toString()}.
     * @return a string representation of these headers
     */
    String toString(Collection<String> toSuppress);

}
