package io.muserver;

import javax.ws.rs.core.MediaType;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static io.muserver.Mutils.notNull;

class MuHeaders implements Headers {

    private final TreeMap<String, List<String>> all = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    @Override
    public Map<String, List<String>> all() {
        return all;
    }

    public MuHeaders set(String header, String value) {
        notNull("name", header);
        notNull("value", value);
        all.put(header, newList(value));
        return this;
    }

    public MuHeaders add(String header, String value) {
        notNull("name", header);
        notNull("value", value);
        if (all.containsKey(header)) {
            all.get(header).add(value);
        } else {
            set(header, value);
        }
        return this;
    }


    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MuHeaders muHeaders = (MuHeaders) o;
        return Objects.equals(all, muHeaders.all);
    }

    @Override
    public int hashCode() {
        return Objects.hash(all);
    }

    @Override
    public String toString() {
        return toString(null);
    }



    public static final MuHeaders EMPTY = new MuHeaders() {
        @Override
        public Headers add(String name, Object value) {
            throw new UnsupportedOperationException("This is a readonly object");
        }

        @Override
        public Headers add(CharSequence name, Object value) {
            throw new UnsupportedOperationException("This is a readonly object");
        }

        @Override
        public Headers add(String name, Iterable<?> values) {
            throw new UnsupportedOperationException("This is a readonly object");
        }

        @Override
        public Headers add(CharSequence name, Iterable<?> values) {
            throw new UnsupportedOperationException("This is a readonly object");
        }

        @Override
        public Headers add(Headers headers) {
            throw new UnsupportedOperationException("This is a readonly object");
        }

        @Override
        public Headers addInt(CharSequence name, int value) {
            throw new UnsupportedOperationException("This is a readonly object");
        }


        @Override
        public Headers set(String name, Object value) {
            throw new UnsupportedOperationException("This is a readonly object");
        }

        @Override
        public Headers set(CharSequence name, Object value) {
            throw new UnsupportedOperationException("This is a readonly object");
        }

        @Override
        public Headers set(String name, Iterable<?> values) {
            throw new UnsupportedOperationException("This is a readonly object");
        }

        @Override
        public Headers set(CharSequence name, Iterable<?> values) {
            throw new UnsupportedOperationException("This is a readonly object");
        }

        @Override
        public Headers set(Headers headers) {
            throw new UnsupportedOperationException("This is a readonly object");
        }

        @Override
        public Headers setAll(Headers headers) {
            throw new UnsupportedOperationException("This is a readonly object");
        }

        @Override
        public Headers setInt(CharSequence name, int value) {
            throw new UnsupportedOperationException("This is a readonly object");
        }


        @Override
        public Headers remove(String name) {
            throw new UnsupportedOperationException("This is a readonly object");
        }

        @Override
        public Headers remove(CharSequence name) {
            throw new UnsupportedOperationException("This is a readonly object");
        }
    };


    @Override
    @Deprecated
    public String get(CharSequence name) {
        return get(name.toString());
    }

    @Override
    @Deprecated
    public String get(CharSequence name, String defaultValue) {
        return get(name.toString(), defaultValue);
    }

    @Override
    @Deprecated
    public int getInt(CharSequence name, int defaultValue) {
        return getInt(name.toString(), defaultValue);
    }


    @Override
    @Deprecated
    public Long getTimeMillis(CharSequence name) {
        return getTimeMillis(name.toString());
    }

    @Override
    @Deprecated
    public long getTimeMillis(CharSequence name, long defaultValue) {
        return getTimeMillis(name.toString(), defaultValue);
    }

    public Long getTimeMillis(String name) {
        String val = get(name, null);
        if (val == null) {
            return null;
        }
        Instant parsed = DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC)
            .parse(val, Instant::from);
        return parsed.toEpochMilli();
    }

    public long getTimeMillis(String name, long defaultValue) {
        Long val = getTimeMillis(name);
        return val == null ? defaultValue : val;
    }

    @Override
    @Deprecated
    public List<String> getAll(CharSequence name) {
        return getAll(name.toString());
    }

    @Override
    @Deprecated
    public List<Map.Entry<String, String>> entries() {
        if (isEmpty()) {
            return Collections.emptyList();
        }
        List<Map.Entry<String, String>> entriesConverted = new ArrayList<>(all.size());
        for (Map.Entry<String, List<String>> entry : all.entrySet()) {
            for (String value : entry.getValue()) {
                entriesConverted.add(Map.entry(entry.getKey(), value));
            }
        }
        return entriesConverted;
    }




    @Override
    @Deprecated
    public boolean contains(CharSequence name) {
        return contains(name.toString());
    }


    @Override
    @Deprecated
    public Set<String> names() {
        return all.keySet();
    }

    @Override
    public Headers add(String name, Object value) {
        return add(name, valueToString(value));
    }

    private static ArrayList<String> newList(String s) {
        ArrayList<String> values = new ArrayList<>(1);
        values.add(s);
        return values;
    }

    private static String valueToString(Object value) {
        notNull("value", value);
        return value instanceof Date ? Mutils.toHttpDate((Date) value) : value.toString();
    }

    @Override
    @Deprecated
    public Headers add(CharSequence name, Object value) {
        return add(name.toString(), value);
    }

    @Override
    public Headers add(String name, Iterable<?> values) {
        List<String> cur = addIfAbsent(name);
        for (Object next : values) {
            cur.add(valueToString(next));
        }
        return this;
    }

    @Override
    @Deprecated
    public Headers add(CharSequence name, Iterable<?> values) {
        return add(name.toString(), values);
    }

    @Override
    @Deprecated
    public Headers add(Headers headers) {
        MuHeaders muHeaders = (MuHeaders) headers;
        Map<String, List<String>> all = muHeaders.all();
        for (Map.Entry<String, List<String>> entry : all.entrySet()) {
            add(entry.getKey(), entry.getValue());
        }
        return this;
    }

    @Override
    @Deprecated
    public Headers addInt(CharSequence name, int value) {
        throw new MuException("Deprecated");
    }


    @Override
    public Headers set(String name, Object value) {
        return set(name, valueToString(value));
    }

    @Override
    @Deprecated
    public Headers set(CharSequence name, Object value) {
        return set(name.toString(), value);
    }

    @Override
    public Headers set(String name, Iterable<?> values) {
        remove(name);
        return add(name, values);
    }

    public Headers set(String name, List<String> values) {
        all.put(name, values);
        return this;
    }

    private List<String> addIfAbsent(String name) {
        List<String> values = all.get(name);
        if (values == null) {
            values = new ArrayList<>(1);
            all.put(name, values);
        }
        return values;
    }

    @Override
    @Deprecated
    public Headers set(CharSequence name, Iterable<?> values) {
        return set(name.toString(), values);
    }

    @Override
    @Deprecated
    public Headers set(Headers headers) {
        clear();
        return setAll(headers);
    }

    @Override
    public Headers setAll(Headers headers) {
        notNull("headers", headers);
        for (Map.Entry<String, List<String>> header : ((MuHeaders)headers).all().entrySet()) {
            set(header.getKey(), header.getValue());
        }
        return this;
    }

    @Override
    @Deprecated
    public Headers setInt(CharSequence name, int value) {
        throw new MuException("Deprecated");
    }


    @Override
    public Headers remove(String name) {
        notNull("name", name);
        all.remove(name);
        return this;
    }

    @Override
    @Deprecated
    public Headers remove(CharSequence name) {
        return remove(name.toString());
    }

    @Override
    public Headers clear() {
        all.clear();
        return this;
    }

    @Override
    @Deprecated
    public boolean contains(String name, String value, boolean ignoreCase) {
        return containsValue(name, value, ignoreCase);
    }

    @Override
    @Deprecated
    public boolean containsValue(CharSequence name, CharSequence value, boolean ignoreCase) {
        return containsValue(name.toString(), value.toString(), ignoreCase);
    }

    public boolean containsValue(String name, String value, boolean ignoreCaseOfValue) {
        List<String> all = getAll(name);
        for (String s : all) {
            if (ignoreCaseOfValue && value.equalsIgnoreCase(s)) {
                return true;
            }
            if (!ignoreCaseOfValue && value.equals(s)) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Deprecated
    public boolean contains(CharSequence name, CharSequence value, boolean ignoreCase) {
        return containsValue(name.toString(), value.toString(), ignoreCase);
    }

    @Override
    public boolean hasBody() {
        return contains(HeaderNames.TRANSFER_ENCODING) || getInt(HeaderNames.CONTENT_LENGTH, -1) > 0;
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
