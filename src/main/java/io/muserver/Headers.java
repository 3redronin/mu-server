package io.muserver;

import javax.ws.rs.core.MediaType;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public interface Headers extends Iterable<Map.Entry<String, String>> {
    /**
     * <p>Gets the value with the given name, or null if there is no parameter with that name.</p>
     * <p>If there are multiple parameters with the same name, the first one is returned.</p>
     *
     * @param name The name of the parameter to get
     * @return The value, or null
     */
    String get(String name);

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

    @Deprecated
    Integer getInt(CharSequence name);

    /**
     * Gets the parameter as an integer, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as an integer.
     */
    int getInt(CharSequence name, int defaultValue);

    /**
     * Gets the parameter as a long, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as a long.
     */
    long getLong(String name, long defaultValue);

    /**
     * Gets the parameter as a float, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as a float.
     */
    float getFloat(String name, float defaultValue);

    /**
     * Gets the parameter as a double, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as a double.
     */
    double getDouble(String name, double defaultValue);

    /**
     * <p>Gets a parameter as a boolean, where values such as <code>true</code>, <code>on</code> and <code>yes</code> as
     * considered true, and other values (or no parameter with the name) is considered false.</p>
     * <p>This can be used to access checkbox values as booleans.</p>
     * @param name The name of the parameter.
     * @return Returns true if the value was truthy, or false if it was falsy or not specified.
     */
    boolean getBoolean(String name);

    @Deprecated
    Short getShort(CharSequence name);

    @Deprecated
    short getShort(CharSequence name, short defaultValue);

    Long getTimeMillis(CharSequence name);

    long getTimeMillis(CharSequence name, long defaultValue);

    /**
     * Gets all the parameters with the given name, or an empty list if none are found.
     *
     * @param name The parameter name to get
     * @return All values of the parameter with the given name
     */
    List<String> getAll(String name);

    /**
     * Gets all the parameters with the given name, or an empty list if none are found.
     *
     * @param name The parameter name to get
     * @return All values of the parameter with the given name
     */
    List<String> getAll(CharSequence name);

    List<Map.Entry<String, String>> entries();

    /**
     * Returns true if the given parameter is specified with any value
     * @param name The name of the value
     * @return True if it's specified; otherwise false.
     */
    boolean contains(String name);

    /**
     * Returns true if the given parameter is specified with any value
     * @param name The name of the value
     * @return True if it's specified; otherwise false.
     */
    boolean contains(CharSequence name);

    Iterator<Map.Entry<String, String>> iterator();

    boolean isEmpty();

    int size();

    Set<String> names();

    Headers add(String name, Object value);

    Headers add(CharSequence name, Object value);

    Headers add(String name, Iterable<?> values);

    Headers add(CharSequence name, Iterable<?> values);

    Headers add(Headers headers);

    Headers addInt(CharSequence name, int value);

    @Deprecated
    Headers addShort(CharSequence name, short value);

    Headers set(String name, Object value);

    Headers set(CharSequence name, Object value);

    Headers set(String name, Iterable<?> values);

    Headers set(CharSequence name, Iterable<?> values);

    Headers set(Headers headers);

    Headers setAll(Headers headers);

    Headers setInt(CharSequence name, int value);

    @Deprecated
    Headers setShort(CharSequence name, short value);

    Headers remove(String name);

    Headers remove(CharSequence name);

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

    @Deprecated
    String getAsString(CharSequence name);

    @Deprecated
    List<String> getAllAsString(CharSequence name);

    @Deprecated
    Iterator<Map.Entry<String, String>> iteratorAsString();

    boolean equals(Object o);

    int hashCode();

    String toString();

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
     * @return The media type of the content specified by the headers.
     */
    MediaType contentType();

    static Headers http1Headers() {
        return new H1Headers();
    }

    static Headers http2Headers() {
        return new H2Headers();
    }
}
