package io.muserver.rest;

import jakarta.ws.rs.core.AbstractMultivaluedMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jspecify.annotations.Nullable;

import java.util.AbstractList;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * A live string view of an object-valued headers map.
 */
class StringHeadersMap extends AbstractMultivaluedMap<String, String> {

    StringHeadersMap(MultivaluedMap<String, Object> headers, Function<Object, String> converter) {
        super(new StringHeadersView(headers, converter));
    }

    private static class StringHeadersView extends AbstractMap<String, List<String>> {
        private final MultivaluedMap<String, Object> headers;
        private final Function<Object, String> converter;

        private StringHeadersView(MultivaluedMap<String, Object> headers, Function<Object, String> converter) {
            this.headers = headers;
            this.converter = converter;
        }

        @Override
        public @Nullable List<String> get(Object key) {
            List<Object> values = headers.get(key);
            return values == null ? null : asStringList(values);
        }

        @Override
        public boolean containsKey(Object key) {
            return headers.containsKey(key);
        }

        @Override
        public @Nullable List<String> put(String key, List<String> value) {
            List<Object> previous = headers.put(key, asObjectList(value));
            return previous == null ? null : asStringSnapshot(previous);
        }

        @Override
        public @Nullable List<String> remove(Object key) {
            List<Object> removed = headers.remove(key);
            return removed == null ? null : asStringSnapshot(removed);
        }

        @Override
        public void clear() {
            headers.clear();
        }

        @Override
        public int size() {
            return headers.size();
        }

        @Override
        public Set<Entry<String, List<String>>> entrySet() {
            return new AbstractSet<Entry<String, List<String>>>() {
                @Override
                public Iterator<Entry<String, List<String>>> iterator() {
                    Iterator<Entry<String, List<Object>>> entries = headers.entrySet().iterator();
                    return new Iterator<Entry<String, List<String>>>() {
                        @Override
                        public boolean hasNext() {
                            return entries.hasNext();
                        }

                        @Override
                        public Entry<String, List<String>> next() {
                            Entry<String, List<Object>> entry = entries.next();
                            return new Entry<String, List<String>>() {
                                @Override
                                public String getKey() {
                                    return entry.getKey();
                                }

                                @Override
                                public List<String> getValue() {
                                    return asStringList(entry.getValue());
                                }

                                @Override
                                public List<String> setValue(List<String> value) {
                                    return asStringSnapshot(entry.setValue(asObjectList(value)));
                                }

                                @Override
                                public boolean equals(@Nullable Object obj) {
                                    if (!(obj instanceof Entry)) {
                                        return false;
                                    }
                                    Entry<?, ?> other = (Entry<?, ?>) obj;
                                    return Objects.equals(getKey(), other.getKey())
                                        && Objects.equals(getValue(), other.getValue());
                                }

                                @Override
                                public int hashCode() {
                                    return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
                                }
                            };
                        }

                        @Override
                        public void remove() {
                            entries.remove();
                        }
                    };
                }

                @Override
                public int size() {
                    return headers.size();
                }

                @Override
                public void clear() {
                    headers.clear();
                }
            };
        }

        private List<String> asStringList(List<Object> values) {
            return new AbstractList<String>() {
                @Override
                public String get(int index) {
                    return converter.apply(values.get(index));
                }

                @Override
                public int size() {
                    return values.size();
                }

                @Override
                public String set(int index, String element) {
                    return converter.apply(values.set(index, element));
                }

                @Override
                public void add(int index, String element) {
                    values.add(index, element);
                }

                @Override
                public String remove(int index) {
                    return converter.apply(values.remove(index));
                }
            };
        }

        private List<String> asStringSnapshot(List<Object> values) {
            List<String> snapshot = new ArrayList<>(values.size());
            for (Object value : values) {
                snapshot.add(converter.apply(value));
            }
            return snapshot;
        }

        private static List<Object> asObjectList(List<String> values) {
            return new ArrayList<>(values);
        }
    }
}
