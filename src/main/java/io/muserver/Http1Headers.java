package io.muserver;

import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import java.util.*;

import static io.muserver.NettyRequestParameters.isTruthy;
import static io.netty.util.internal.ObjectUtil.checkNotNull;

class Http1Headers implements Headers {

    private final HttpHeaders entries;

    Http1Headers() {
        this(new DefaultHttpHeaders());
    }

    Http1Headers(HttpHeaders entries) {
        this.entries = entries;
    }

    @Override
    public String get(String name) {
        return entries.get(name);
    }

    @Override
    public String get(CharSequence name) {
        return entries.get(name);
    }

    @Override
    public String get(CharSequence name, String defaultValue) {
        return entries.get(name, defaultValue);
    }

    @Override
    public int getInt(CharSequence name, int defaultValue) {
        return entries.getInt(name, defaultValue);
    }

    @Override
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

    @Override
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

    @Override
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

    @Override
    public boolean getBoolean(String name) {
        String val = get(name, "").toLowerCase();
        return isTruthy(val);
    }

    @Override
    public Long getTimeMillis(CharSequence name) {
        return entries.getTimeMillis(name);
    }

    @Override
    public long getTimeMillis(CharSequence name, long defaultValue) {
        return entries.getTimeMillis(name, defaultValue);
    }

    @Override
    public List<String> getAll(String name) {
        return entries.getAll(name);
    }

    @Override
    public List<String> getAll(CharSequence name) {
        return entries.getAll(name);
    }

    @Override
    public List<Map.Entry<String, String>> entries() {
        return entries.entries();
    }

    @Override
    public boolean contains(String name) {
        return entries.contains(name);
    }

    @Override
    public boolean contains(CharSequence name) {
        return entries.contains(name);
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
        return entries.iteratorAsString();
    }

    @Override
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public Set<String> names() {
        return entries.names();
    }

    @Override
    public Headers add(String name, Object value) {
        entries.add(name, value);
        return this;
    }

    @Override
    public Headers add(CharSequence name, Object value) {
        entries.add(name, value);
        return this;
    }

    @Override
    public Headers add(String name, Iterable<?> values) {
        entries.add(name, values);
        return this;
    }

    @Override
    public Headers add(CharSequence name, Iterable<?> values) {
        entries.add(name, values);
        return this;
    }

    @Override
    public Headers add(Headers headers) {
        for (Map.Entry<String, String> e : headers) {
            add(e.getKey(), e.getValue());
        }
        return this;
    }

    @Override
    public Headers addInt(CharSequence name, int value) {
        entries.addInt(name, value);
        return this;
    }

    @Override
    public Headers set(String name, Object value) {
        entries.set(name, value);
        return this;
    }

    @Override
    public Headers set(CharSequence name, Object value) {
        entries.set(name, value);
        return this;
    }

    @Override
    public Headers set(String name, Iterable<?> values) {
        entries.set(name, values);
        return this;
    }

    @Override
    public Headers set(CharSequence name, Iterable<?> values) {
        entries.set(name, values);
        return this;
    }

    @Override
    public Headers set(Headers headers) {
        checkNotNull(headers, "headers");
        clear();
        for (Map.Entry<String, String> entry : headers) {
            add(entry.getKey(), entry.getValue());
        }
        return this;
    }

    @Override
    public Headers setAll(Headers headers) {
        checkNotNull(headers, "headers");
        for (String name : headers.names()) {
            set(name, headers.getAll(name));
        }
        return this;
    }

    @Override
    public Headers setInt(CharSequence name, int value) {
        entries.setInt(name, value);
        return this;
    }

    @Override
    public Headers remove(String name) {
        entries.remove(name);
        return this;
    }

    @Override
    public Headers remove(CharSequence name) {
        entries.remove(name);
        return this;
    }

    @Override
    public Headers clear() {
        entries.clear();
        return this;
    }

    @Override
    public boolean contains(String name, String value, boolean ignoreCase) {
        return entries.contains(name, value, ignoreCase);
    }

    @Override
    public boolean contains(CharSequence name, CharSequence value, boolean ignoreCase) {
        return entries.contains(name, value, ignoreCase);
    }

    @Override
    public boolean containsValue(CharSequence name, CharSequence value, boolean ignoreCase) {
        return entries.containsValue(name, value, ignoreCase);
    }

    @Override
    public boolean equals(Object o) {
        return entries.equals(o);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return Headtils.toString(this, null);
    }

    @Override
    public String toString(Collection<String> toSuppress) {
        return Headtils.toString(this, toSuppress);
    }

    @Override
    public boolean hasBody() {
        return contains(HeaderNames.TRANSFER_ENCODING) || getLong(HeaderNames.CONTENT_LENGTH.toString(), -1) > 0;
    }

    @Override
    public List<ParameterizedHeaderWithValue> accept() {
        return Headtils.getParameterizedHeaderWithValues(this, HeaderNames.ACCEPT);
    }

    @Override
    public List<ParameterizedHeaderWithValue> acceptCharset() {
        return Headtils.getParameterizedHeaderWithValues(this, HeaderNames.ACCEPT_CHARSET);
    }

    @Override
    public List<ParameterizedHeaderWithValue> acceptEncoding() {
        return Headtils.getParameterizedHeaderWithValues(this, HeaderNames.ACCEPT_ENCODING);
    }

    @Override
    public List<ForwardedHeader> forwarded() {
        return Headtils.getForwardedHeaders(this);
    }

    @Override
    public List<ParameterizedHeaderWithValue> acceptLanguage() {
        return Headtils.getParameterizedHeaderWithValues(this, HeaderNames.ACCEPT_LANGUAGE);
    }

    @Override
    public ParameterizedHeader cacheControl() {
        return ParameterizedHeader.fromString(get(HeaderNames.CACHE_CONTROL));
    }

    @Override
    public MediaType contentType() {
        return Headtils.getMediaType(this);
    }

    @Override
    public TokenListHeader connection() {
        return null;
    }

    @Override
    public TokenListHeader vary() {
        return null;
    }


}
