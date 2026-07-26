package io.muserver.rest;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

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


    @Override
    public void putSingle(K key, V value) {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    @Override
    public void add(K key, V value) {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    @Override
    @SafeVarargs
    public final void addAll(K key, V... newValues) {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    @Override
    public void addAll(K key, List<V> valueList) {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    @Override
    public @Nullable V getFirst(K key) {
        return actual.getFirst(key);
    }

    @Override
    public void addFirst(K key, V value) {
        actual.addFirst(key, value);
    }

    @Override
    public String toString() {
        return "Read Only: " + actual.toString();
    }

    @Override
    public int hashCode() {
        return actual.hashCode();
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return actual.equals(o);
    }

    @Override
    public Collection<List<V>> values() {
        return actual.values();
    }

    @Override
    public int size() {
        return actual.size();
    }

    @Override
    public List<V> remove(@Nullable Object key) {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    @Override
    public void putAll(Map<? extends K, ? extends List<V>> m) {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    @Override
    public List<V> put(K key, List<V> value) {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    @Override
    public Set<K> keySet() {
        return actual.keySet();
    }

    @Override
    public boolean isEmpty() {
        return actual.isEmpty();
    }

    @Override
    public @Nullable List<V> get(@Nullable Object key) {
        return actual.get(key);
    }

    @Override
    public Set<Entry<K, List<V>>> entrySet() {
        return actual.entrySet();
    }

    @Override
    public boolean containsValue(@Nullable Object value) {
        return actual.containsValue(value);
    }

    @Override
    public boolean containsKey(@Nullable Object key) {
        return actual.containsKey(key);
    }

    @Override
    public void clear() {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    @Override
    public boolean equalsIgnoreValueOrder(MultivaluedMap<K, V> omap) {
        return actual.equalsIgnoreValueOrder(omap);
    }
}
