package io.muserver;

import io.netty.handler.codec.http2.DefaultHttp2Headers;
import jakarta.ws.rs.core.MediaType;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static io.muserver.NettyRequestParameters.isTruthy;
import static io.netty.util.internal.ObjectUtil.checkNotNull;

class Http2Headers implements Headers {

    final io.netty.handler.codec.http2.Http2Headers entries;
    private final boolean hasRequestBody;

    Http2Headers() {
        this(new DefaultHttp2Headers(), false);
    }

    Http2Headers(io.netty.handler.codec.http2.Http2Headers entries, boolean hasRequestBody) {
        this.entries = entries;
        this.hasRequestBody = hasRequestBody;
    }

    private static CharSequence toLower(CharSequence name) {
        Mutils.notNull("name", name);
        if (name instanceof String) {
            return ((String) name).toLowerCase();
        }
        return name;
    }

    @Override
    public String get(String name) {
        return get((CharSequence) name);
    }

    @Override
    public String get(CharSequence name) {
        CharSequence val = entries.get(toLower(name));
        return val == null ? null : val.toString();
    }

    @Override
    public String get(CharSequence name, String defaultValue) {
        CharSequence val = entries.get(toLower(name), defaultValue);
        return val == null ? null : val.toString();
    }

    @Override
    @Deprecated
    public Integer getInt(CharSequence name) {
        return entries.getInt(toLower(name));
    }

    @Override
    public int getInt(CharSequence name, int defaultValue) {
        return entries.getInt(toLower(name), defaultValue);
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
    @Deprecated
    public Short getShort(CharSequence name) {
        return entries.getShort(toLower(name));
    }

    @Override
    @Deprecated
    public short getShort(CharSequence name, short defaultValue) {
        return entries.getShort(toLower(name), defaultValue);
    }

    @Override
    public Long getTimeMillis(CharSequence name) {
        return entries.getTimeMillis(toLower(name));
    }

    @Override
    public long getTimeMillis(CharSequence name, long defaultValue) {
        return entries.getTimeMillis(toLower(name), defaultValue);
    }

    @Override
    public List<String> getAll(String name) {
        return getAll((CharSequence) name);
    }

    @Override
    public List<String> getAll(CharSequence name) {
        return entries.getAll(toLower(name)).stream().map(CharSequence::toString).collect(Collectors.toList());
    }

    @Override
    public List<Map.Entry<String, String>> entries() {
        List<Map.Entry<String, String>> all = new ArrayList<>(size());
        for (Map.Entry<String, String> e : this) {
            all.add(e);
        }
        return all;
    }

    @Override
    public boolean contains(String name) {
        return contains((CharSequence) name);
    }

    @Override
    public boolean contains(CharSequence name) {
        return entries.contains(toLower(name));
    }

    @Override
    public Iterator<Map.Entry<String, String>> iterator() {
        Iterator<Map.Entry<CharSequence, CharSequence>> it = entries.iterator();

        return Stream.generate(it::next).limit(entries.size())
            .filter(e -> e.getKey().charAt(0) != ':')
            .map(e -> (Map.Entry<String, String>) new AbstractMap.SimpleImmutableEntry<>(e.getKey().toString(), e.getValue().toString()))
            .iterator();
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
        return entries.names().stream()
            .filter(name -> name.charAt(0) != ':')
            .map(CharSequence::toString).collect(Collectors.toSet());
    }

    @Override
    public Headers add(String name, Object value) {
        return add((CharSequence) name, value);
    }


    @Override
    public Headers add(CharSequence name, Object value) {
        entries.addObject(toLower(name), value);
        return this;
    }

    @Override
    public Headers add(String name, Iterable<?> values) {
        return add((CharSequence) name, values);
    }

    @Override
    public Headers add(CharSequence name, Iterable<?> values) {
        name = toLower(name);
        for (Object value : values) {
            entries.addObject(name, value);
        }
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
        entries.addInt(toLower(name), value);
        return this;
    }

    @Override
    @Deprecated
    public Headers addShort(CharSequence name, short value) {
        entries.addShort(toLower(name), value);
        return this;
    }

    @Override
    public Headers set(String name, Object value) {
        return set((CharSequence) name, value);
    }

    @Override
    public Headers set(CharSequence name, Object value) {
        entries.setObject(toLower(name), value);
        return this;
    }

    @Override
    public Headers set(String name, Iterable<?> values) {
        return set((CharSequence) name, values);
    }

    @Override
    public Headers set(CharSequence name, Iterable<?> values) {
        name = toLower(name);
        entries.remove(name);
        return add(name, values);
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
        entries.setInt(toLower(name), value);
        return this;
    }

    @Override
    @Deprecated
    public Headers setShort(CharSequence name, short value) {
        entries.setShort(toLower(name), value);
        return this;
    }

    @Override
    public Headers remove(String name) {
        return remove((CharSequence) name);
    }

    @Override
    public Headers remove(CharSequence name) {
        entries.remove(toLower(name));
        return this;
    }

    @Override
    public Headers clear() {
        entries.clear();
        return this;
    }

    @Override
    public boolean contains(String name, String value, boolean ignoreCase) {
        return contains((CharSequence) name, value, ignoreCase);
    }

    @Override
    public boolean contains(CharSequence name, CharSequence value, boolean ignoreCase) {
        return entries.contains(toLower(name), value, ignoreCase);
    }

    @Override
    public boolean containsValue(CharSequence name, CharSequence value, boolean ignoreCase) {
        return entries.contains(toLower(name), value, ignoreCase);
    }

    @Override
    @Deprecated
    public String getAsString(CharSequence name) {
        return get(name);
    }

    @Override
    @Deprecated
    public List<String> getAllAsString(CharSequence name) {
        return getAll(name);
    }

    @Override
    @Deprecated
    public Iterator<Map.Entry<String, String>> iteratorAsString() {
        return iterator();
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
        return hasRequestBody;
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
}
