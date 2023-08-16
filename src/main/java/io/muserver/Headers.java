package io.muserver;

import javax.ws.rs.core.MediaType;
import java.util.*;

/**
 * HTTP headers
 */
public interface Headers extends Iterable<Map.Entry<String, String>>, RequestParameters {

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
     * @param name The name of the parameter to get
     * @param defaultValue The default value to use if there is no given value
     * @return The value of the parameter, or the default value
     */
    String get(CharSequence name, String defaultValue);

    /**
     * Gets the parameter as an integer, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as an integer.
     */
    int getInt(CharSequence name, int defaultValue);

    /**
     * Gets a date header.
     * <p>This converts the string date values into milliseconds</p>
     * @param name The header name
     * @return The value in milliseconds of the date header, or null if not found
     */
    Long getTimeMillis(CharSequence name);

    /**
     * Gets a date header.
     * <p>This converts the string date values into milliseconds</p>
     * @param name The header name
     * @param defaultValue The default to use if no date header is available
     * @return The value in milliseconds of the date header, or the default if not found
     */
    long getTimeMillis(CharSequence name, long defaultValue);

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
     * @param name The name of the value
     * @return True if it's specified; otherwise false.
     */
    boolean contains(CharSequence name);

    /**
     * @return An iterator to iterate through the headers
     */
    default Iterator<Map.Entry<String, String>> iterator() {
        return entries().iterator();
    }



    /**
     * @return The header names in these headers
     */
    Set<String> names();

    /**
     * Adds an item to these headers. If a header with the given name already exists then this value is added
     * rather than replaced.
     * @param name The header name
     * @param value The value
     * @return This headers object
     */
    Headers add(String name, Object value);

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
     * @param name The header name
     * @param values The values
     * @return This headers object
     */
    Headers add(String name, Iterable<?> values);

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
     * @param name The header name
     * @param value The value
     * @return This headers object
     */
    Headers addInt(CharSequence name, int value);

    /**
     * Sets a header value. If a header with the given name already exists then this replaces it.
     * @param name The header name
     * @param value The value
     * @return This headers object
     */
    Headers set(String name, Object value);

    /**
     * Sets a header value. If a header with the given name already exists then this replaces it.
     * @param name The header name
     * @param value The value
     * @return This headers object
     */
    Headers set(CharSequence name, Object value);

    /**
     * Sets a header value list. If a header with the given name already exists then this replaces it.
     * @param name The header name
     * @param values The value
     * @return This headers object
     */
    Headers set(String name, Iterable<?> values);

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
     * @param name The header name
     * @param value The value to set
     * @return This headers object
     */
    Headers setInt(CharSequence name, int value);

    /**
     * Removes a header. Does nothing if the header is not set.
     * @param name The header to remove
     * @return This headers object
     */
    Headers remove(String name);

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
     * @param name The case-insensitive header name to check.
     * @param value The value to check for.
     * @param ignoreCase If true, the case of the value is ignored.
     * @return True if a header name with the given value exists.
     */
    boolean contains(String name, String value, boolean ignoreCase);

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
    boolean hasBody();

    /**
     * <p>Gets the <code>Accept-Charset</code> header value.</p>
     * <p>For example, if a client sends <code>text/html,application/xml;q=0.9</code>
     * then this would return a list of two values: text/html, and application/xml where application/xml
     * has a parameter <code>q</code> of value <code>0.9</code></p>
     *
     * @return Returns a parsed <code>Accept</code> header, or an empty list if none specified.
     */
    List<ParameterizedHeaderWithValue> accept();

    /**
     * <p>Gets the <code>Accept</code> header value.</p>
     * <p>For example, if a client sends <code>iso-8859-5, unicode-1-1;q=0.8</code>
     * then this would return a list of two values: iso-8859-5, and unicode-1-1 where unicode-1-1
     * has a parameter <code>q</code> of value <code>0.8</code></p>
     *
     * @return Returns a parsed <code>Accept-Charset</code> header, or an empty list if none specified.
     */
    List<ParameterizedHeaderWithValue> acceptCharset();

    /**
     * <p>Gets the <code>Accept-Encoding</code> header value.</p>
     * <p>For example, if a client sends <code>gzip, deflate</code>
     * then this would return a list of two values: gzip and deflate</p>
     *
     * @return Returns a parsed <code>Accept-Encoding</code> header, or an empty list if none specified.
     */
    List<ParameterizedHeaderWithValue> acceptEncoding();

    /**
     * <p>Gets the <code>Forwarded</code> header value.</p>
     * <p>For example, if a client sends <code>for=192.0.2.60;proto=http;by=203.0.113.43</code>
     * then this would return a list of length one that has for, proto, and by values.</p>
     * <p>If there is no Forwarded header, however there are X-Forwarded-* headers, then those
     * will be used to generate pseudo-forwarded headers.</p>
     *
     * @return Returns a parsed <code>Forwarded</code> header, or an empty list if none specified.
     */
    List<ForwardedHeader> forwarded();

    /**
     * <p>Gets the <code>Accept-Language</code> header value.</p>
     * <p>For example, if a client sends <code>en-US,en;q=0.5</code>
     * then this would return a list of two values: en-US and en where en has a <code>q</code> value of <code>0.5</code></p>
     *
     * @return Returns a parsed <code>Accept-Language</code> header, or an empty list if none specified.
     */
    List<ParameterizedHeaderWithValue> acceptLanguage();

    /**
     * Gets the <code>Cache-Control</code> header value.
     *
     * @return A map of cache control directives to their optional values. If no cache-control
     * is in the header, then the resulting map will be empty.
     */
    ParameterizedHeader cacheControl();

    /**
     * Gets the parsed <code>Content-Type</code> header value.
     *
     * @return The media type of the content specified by the headers, or <code>null</code> if not set.
     */
    MediaType contentType();

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
     * Returns a string representation of the headers.
     *
     * <strong>Note:</strong> The following headers will have their actual values replaced with the string <code>(hidden)</code>
     * in order to protect potentially sensitive information: <code>authorization</code>, <code>cookie</code> and <code>set-cookie</code>.
     * <p>If you wish to print all values or customize the header values that are hidden, use {@link #toString(Collection)}</p>
     * @return a string representation of these headers
     */
    String toString();

    /**
     * Returns a string representation of the headers with selected header values replaced with the string <code>(hidden)</code>.
     * <p>This may be useful where headers are logged for diagnostic purposes while not revealing values that are held in
     * potentially sensitive headers.</p>
     * @param toSuppress A collection of case-insensitive header names which will not have their values printed.
     *                   Pass an empty collection to print all header values. A <code>null</code> value will hide
     *                   the header values as defined on {@link #toString()}.
     * @return a string representation of these headers
     */
    default String toString(Collection<String> toSuppress) {
        var size = size();
        String simpleName = getClass().getSimpleName();
        if (size == 0) {
            return simpleName + "[]";
        }
        var sb = new StringBuilder().append(simpleName).append('[');
        Headtils.RedactorIterator iterator = new Headtils.RedactorIterator(iterator(), toSuppress);
        while (iterator.hasNext()) {
            Map.Entry<String, String> header = iterator.next();
            sb.append(header.getKey()).append(": ").append(header.getValue());
            if (iterator.hasNext()) sb.append(", ");
        }
        return sb.append(']').toString();
    }


    /**
     * Creates new headers for HTTP1 requests
     * @return An empty headers object.
     */
    static Headers http1Headers() {
        return new Http1Headers();
    }

    /**
     * Creates new headers for HTTP2 requests
     * @return An empty headers object.
     */
    static Headers http2Headers() {
        return new Http2Headers();
    }
}
