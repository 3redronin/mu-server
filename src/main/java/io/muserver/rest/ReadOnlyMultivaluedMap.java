package io.muserver.rest;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A read only version of the multi-valued map
 * @param <K> The key type
 * @param <V> The value type
 */
class ReadOnlyMultivaluedMap<K, V> implements MultivaluedMap<K, V>, Serializable {
    private static final MultivaluedMap EMPTY = readOnly(new MultivaluedHashMap<>());
    private final MultivaluedMap<K, V> actual;

    private ReadOnlyMultivaluedMap(MultivaluedMap<K, V> actual) {
        this.actual = actual;
    }

    static <K, V> MultivaluedMap<K, V> readOnly(MultivaluedMap<K, V> map) {
        return new ReadOnlyMultivaluedMap<>(map);
    }

    @SuppressWarnings("unchecked")
    static <K, V> MultivaluedMap<K, V> empty() {
        return EMPTY;
    }


    public void putSingle(K key, V value) {
        throw new UnsupportedOperationException("This map is read only");
    }

    public void add(K key, V value) {
        throw new UnsupportedOperationException("This map is read only");
    }

    @SafeVarargs
    public final void addAll(K key, V... newValues) {
        throw new UnsupportedOperationException("This map is read only");
    }

    public void addAll(K key, List<V> valueList) {
        throw new UnsupportedOperationException("This map is read only");
    }

    public V getFirst(K key) {
        return actual.getFirst(key);
    }

    public void addFirst(K key, V value) {
        throw new UnsupportedOperationException("This map is read only");
    }

    public String toString() {
        return "Read Only: " + actual.toString();
    }

    public int hashCode() {
        return actual.hashCode();
    }

    public boolean equals(Object o) {
        return actual.equals(o);
    }

    public Collection<List<V>> values() {
        return Collections.unmodifiableList(actual.values().stream()
            .map(Collections::unmodifiableList)
            .collect(Collectors.toList()));
    }

    public int size() {
        return actual.size();
    }

    public List<V> remove(Object key) {
        throw new UnsupportedOperationException("This map is read only");
    }

    public void putAll(Map<? extends K, ? extends List<V>> m) {
        throw new UnsupportedOperationException("This map is read only");
    }

    public List<V> put(K key, List<V> value) {
        throw new UnsupportedOperationException("This map is read only");
    }

    public Set<K> keySet() {
        return Collections.unmodifiableSet(actual.keySet());
    }

    public boolean isEmpty() {
        return actual.isEmpty();
    }

    public List<V> get(Object key) {
        List<V> values = actual.get(key);
        return values == null ? null : Collections.unmodifiableList(values);
    }

    public Set<Entry<K, List<V>>> entrySet() {
        Set<Entry<K, List<V>>> entries = actual.entrySet().stream()
            .map(entry -> new SimpleImmutableEntry<>(entry.getKey(), Collections.unmodifiableList(entry.getValue())))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(entries);
    }

    public boolean containsValue(Object value) {
        return actual.containsValue(value);
    }

    public boolean containsKey(Object key) {
        return actual.containsKey(key);
    }

    public void clear() {
        throw new UnsupportedOperationException("This map is read only");
    }

    public boolean equalsIgnoreValueOrder(MultivaluedMap<K, V> omap) {
        return actual.equalsIgnoreValueOrder(omap);
    }
}
