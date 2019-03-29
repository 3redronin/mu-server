package io.muserver;

import io.netty.handler.codec.HeadersUtils;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;

import javax.ws.rs.core.MediaType;
import java.util.*;

import static io.muserver.NettyRequestParameters.isTruthy;
import static io.netty.util.internal.ObjectUtil.checkNotNull;
import static java.util.Collections.emptyList;

public class Headers implements Iterable<Map.Entry<String, String>> {

    private final HttpHeaders entries;

    public Headers() {
        this(new DefaultHttpHeaders());
    }

    Headers(HttpHeaders entries) {
        this.entries = entries;
    }

    /**
     * <p>Gets the value with the given name, or null if there is no parameter with that name.</p>
     * <p>If there are multiple parameters with the same name, the first one is returned.</p>
     *
     * @param name The name of the parameter to get
     * @return The value, or null
     */
    public String get(String name) {
        return entries.get(name);
    }

    /**
     * <p>Gets the value with the given name, or null if there is no parameter with that name.</p>
     * <p>If there are multiple parameters with the same name, the first one is returned.</p>
     *
     * @param name The name of the parameter to get
     * @return The value, or null
     */
    public String get(CharSequence name) {
        return entries.get(name);
    }

    /**
     * <p>Gets the value with the given name, or the default value if there is no parameter with that name.</p>
     * <p>If there are multiple parameters with the same name, the first one is returned.</p>
     *
     * @param name The name of the parameter to get
     * @param defaultValue The default value to use if there is no given value
     * @return The value of the parameter, or the default value
     */
    public String get(CharSequence name, String defaultValue) {
        return entries.get(name, defaultValue);
    }

    @Deprecated
    public Integer getInt(CharSequence name) {
        return entries.getInt(name);
    }

    /**
     * Gets the parameter as an integer, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as an integer.
     */
    public int getInt(CharSequence name, int defaultValue) {
        return entries.getInt(name, defaultValue);
    }

    /**
     * Gets the parameter as a long, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as a long.
     */
    public long getLong(String name, long defaultValue) {
        try {
            String stringVal = get(name, null);
            if (stringVal == null) {
                return defaultValue;
            }
            return Long.parseLong(stringVal, 10);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Gets the parameter as a float, or returns the default value if it was not specified or was in an invalid format.
     * @param name The name of the parameter.
     * @param defaultValue The value to use if none was specified, or an invalid format was used.
     * @return Returns the parameter value as a float.
     */
    public float getFloat(String name, float defaultValue) {
        try {
            String stringVal = get(name, null);
            if (stringVal == null) {
                return defaultValue;
            }
            return Float.parseFloat(stringVal);
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
    public double getDouble(String name, double defaultValue) {
        try {
            String stringVal = get(name, null);
            if (stringVal == null) {
                return defaultValue;
            }
            return Double.parseDouble(stringVal);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * <p>Gets a parameter as a boolean, where values such as <code>true</code>, <code>on</code> and <code>yes</code> as
     * considered true, and other values (or no parameter with the name) is considered false.</p>
     * <p>This can be used to access checkbox values as booleans.</p>
     * @param name The name of the parameter.
     * @return Returns true if the value was truthy, or false if it was falsy or not specified.
     */
    public boolean getBoolean(String name) {
        String val = get(name, "").toLowerCase();
        return isTruthy(val);
    }

    @Deprecated
    public Short getShort(CharSequence name) {
        return entries.getShort(name);
    }

    @Deprecated
    public short getShort(CharSequence name, short defaultValue) {
        return entries.getShort(name, defaultValue);
    }

    public Long getTimeMillis(CharSequence name) {
        return entries.getTimeMillis(name);
    }

    public long getTimeMillis(CharSequence name, long defaultValue) {
        return entries.getTimeMillis(name, defaultValue);
    }

    /**
     * Gets all the parameters with the given name, or an empty list if none are found.
     *
     * @param name The parameter name to get
     * @return All values of the parameter with the given name
     */
    public List<String> getAll(String name) {
        return entries.getAll(name);
    }

    /**
     * Gets all the parameters with the given name, or an empty list if none are found.
     *
     * @param name The parameter name to get
     * @return All values of the parameter with the given name
     */
    public List<String> getAll(CharSequence name) {
        return entries.getAll(name);
    }

    public List<Map.Entry<String, String>> entries() {
        return entries.entries();
    }

    /**
     * Returns true if the given parameter is specified with any value
     * @param name The name of the value
     * @return True if it's specified; otherwise false.
     */
    public boolean contains(String name) {
        return entries.contains(name);
    }

    /**
     * Returns true if the given parameter is specified with any value
     * @param name The name of the value
     * @return True if it's specified; otherwise false.
     */
    public boolean contains(CharSequence name) {
        return entries.contains(name);
    }

    public Iterator<Map.Entry<String, String>> iterator() {
        return entries.iteratorAsString();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public Set<String> names() {
        return entries.names();
    }

    public Headers add(String name, Object value) {
        entries.add(name, value);
        return this;
    }

    public Headers add(CharSequence name, Object value) {
        entries.add(name, value);
        return this;
    }

    public Headers add(String name, Iterable<?> values) {
        entries.add(name, values);
        return this;
    }

    public Headers add(CharSequence name, Iterable<?> values) {
        entries.add(name, values);
        return this;
    }

    public Headers add(Headers headers) {
        for (Map.Entry<String, String> e : headers) {
            add(e.getKey(), e.getValue());
        }
        return this;
    }

    public Headers addInt(CharSequence name, int value) {
        entries.addInt(name, value);
        return this;
    }

    @Deprecated
    public Headers addShort(CharSequence name, short value) {
        entries.addShort(name, value);
        return this;
    }

    public Headers set(String name, Object value) {
        entries.set(name, value);
        return this;
    }

    public Headers set(CharSequence name, Object value) {
        entries.set(name, value);
        return this;
    }

    public Headers set(String name, Iterable<?> values) {
        entries.set(name, values);
        return this;
    }

    public Headers set(CharSequence name, Iterable<?> values) {
        entries.set(name, values);
        return this;
    }

    public Headers set(Headers headers) {
        checkNotNull(headers, "headers");
        clear();
        for (Map.Entry<String, String> entry : headers) {
            add(entry.getKey(), entry.getValue());
        }
        return this;
    }

    public Headers setAll(Headers headers) {
        checkNotNull(headers, "headers");
        for (String name : headers.names()) {
            set(name, headers.getAll(name));
        }
        return this;
    }

    public Headers setInt(CharSequence name, int value) {
        entries.setInt(name, value);
        return this;
    }

    @Deprecated
    public Headers setShort(CharSequence name, short value) {
        entries.setShort(name, value);
        return this;
    }

    public Headers remove(String name) {
        entries.remove(name);
        return this;
    }

    public Headers remove(CharSequence name) {
        entries.remove(name);
        return this;
    }

    public Headers clear() {
        entries.clear();
        return this;
    }

    public boolean contains(String name, String value, boolean ignoreCase) {
        return entries.contains(name, value, ignoreCase);
    }

    public boolean containsValue(CharSequence name, CharSequence value, boolean ignoreCase) {
        return entries.containsValue(name, value, ignoreCase);
    }

    public String getAsString(CharSequence name) {
        return entries.getAsString(name);
    }

    public List<String> getAllAsString(CharSequence name) {
        return entries.getAllAsString(name);
    }

    public Iterator<Map.Entry<String, String>> iteratorAsString() {
        return entries.iteratorAsString();
    }

    public boolean contains(CharSequence name, CharSequence value, boolean ignoreCase) {
        return entries.contains(name, value, ignoreCase);
    }

    public boolean equals(Object o) {
        return entries.equals(o);
    }

    public int hashCode() {
        return entries.hashCode();
    }


    public String toString() {
        return HeadersUtils.toString(getClass(), entries.iteratorCharSequence(), size());
    }

    HttpHeaders nettyHeaders() {
        return entries;
    }

    /**
     * Returns true if the headers suggest there is a message body by checking if there is a {@link HeaderNames#TRANSFER_ENCODING}
     * header or the {@link HeaderNames#CONTENT_LENGTH} is greater than 0.
     *
     * @return True if there should be a body; otherwise false;
     */
    public boolean hasBody() {
        return contains(HeaderNames.TRANSFER_ENCODING) || getInt(HeaderNames.CONTENT_LENGTH, -1) > 0;
    }

    /**
     * <p>Gets the <code>Accept-Charset</code> header value.</p>
     * <p>For example, if a client sends <code>text/html,application/xml;q=0.9</code>
     * then this would return a list of two values: text/html, and application/xml where application/xml
     * has a parameter <code>q</code> of value <code>0.9</code></p>
     *
     * @return Returns a parsed <code>Accept</code> header, or an empty list if none specified.
     */
    public List<ParameterizedHeaderWithValue> accept() {
        return getParameterizedHeaderWithValues(HeaderNames.ACCEPT);
    }

    /**
     * <p>Gets the <code>Accept</code> header value.</p>
     * <p>For example, if a client sends <code>iso-8859-5, unicode-1-1;q=0.8</code>
     * then this would return a list of two values: iso-8859-5, and unicode-1-1 where unicode-1-1
     * has a parameter <code>q</code> of value <code>0.8</code></p>
     *
     * @return Returns a parsed <code>Accept-Charset</code> header, or an empty list if none specified.
     */
    public List<ParameterizedHeaderWithValue> acceptCharset() {
        return getParameterizedHeaderWithValues(HeaderNames.ACCEPT_CHARSET);
    }

    /**
     * <p>Gets the <code>Accept-Encoding</code> header value.</p>
     * <p>For example, if a client sends <code>gzip, deflate</code>
     * then this would return a list of two values: gzip and deflate</p>
     *
     * @return Returns a parsed <code>Accept-Encoding</code> header, or an empty list if none specified.
     */
    public List<ParameterizedHeaderWithValue> acceptEncoding() {
        return getParameterizedHeaderWithValues(HeaderNames.ACCEPT_ENCODING);
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
    public List<ForwardedHeader> forwarded() {
        List<String> all = getAll(HeaderNames.FORWARDED);
        if (all.isEmpty()) {

            List<String> hosts = getAll(HeaderNames.X_FORWARDED_HOST);
            List<String> ports = getAll(HeaderNames.X_FORWARDED_PORT);
            List<String> protos = getAll(HeaderNames.X_FORWARDED_PROTO);
            List<String> fors = getAll(HeaderNames.X_FORWARDED_FOR);
            int max = Math.max(Math.max(Math.max(hosts.size(), protos.size()), fors.size()), ports.size());
            if (max == 0) {
                return emptyList();
            }
            List<ForwardedHeader> results = new ArrayList<>();

            boolean includeHost = hosts.size() == max;
            boolean includeProto = protos.size() == max;
            boolean includeFor = fors.size() == max;
            boolean includePort = ports.size() == max;
            String curHost = includePort && !includeHost ? get(HeaderNames.HOST) : null;

            for (int i = 0; i < max; i++) {
                String host = includeHost ? hosts.get(i) : null;
                String port = includePort ? ports.get(i) : null;
                String proto = includeProto ? protos.get(i) : null;
                String forValue = includeFor ? fors.get(i) : null;
                boolean useDefaultPort = port == null || (proto != null &&
                    ((proto.equalsIgnoreCase("http") && "80".equals(port))
                    || proto.equalsIgnoreCase("https") && "443".equals(port)));
                String hostToUse =
                    includeHost ? host
                    : includePort ? curHost
                    : null;
                if (hostToUse != null && !useDefaultPort) {
                    hostToUse = hostToUse.replaceFirst(":[0-9]+$", "") + ":" + port;
                }
                results.add(new ForwardedHeader(null, forValue, hostToUse, proto, null));
            }

            return results;
        } else {
            List<ForwardedHeader> results = new ArrayList<>();
            for (String s : all) {
                results.addAll(ForwardedHeader.fromString(s));
            }
            return results;
        }
    }

    /**
     * <p>Gets the <code>Accept-Language</code> header value.</p>
     * <p>For example, if a client sends <code>en-US,en;q=0.5</code>
     * then this would return a list of two values: en-US and en where en has a <code>q</code> value of <code>0.5</code></p>
     *
     * @return Returns a parsed <code>Accept-Language</code> header, or an empty list if none specified.
     */
    public List<ParameterizedHeaderWithValue> acceptLanguage() {
        return getParameterizedHeaderWithValues(HeaderNames.ACCEPT_LANGUAGE);
    }

    private List<ParameterizedHeaderWithValue> getParameterizedHeaderWithValues(CharSequence headerName) {
        String input = get(headerName);
        if (input == null) {
            return emptyList();
        }
        return ParameterizedHeaderWithValue.fromString(input);
    }

    /**
     * Gets the <code>Cache-Control</code> header value.
     *
     * @return A map of cache control directives to their optional values. If no cache-control
     * is in the header, then the resulting map will be empty.
     */
    public ParameterizedHeader cacheControl() {
        return ParameterizedHeader.fromString(get(HeaderNames.CACHE_CONTROL));
    }

    /**
     * Gets the parsed <code>Content-Type</code> header value.
     *
     * @return The media type of the content specified by the headers.
     */
    public MediaType contentType() {
        String value = get(HeaderNames.CONTENT_TYPE);
        if (value == null) {
            return null;
        }
        return MediaTypeParser.fromString(value);
    }
}
