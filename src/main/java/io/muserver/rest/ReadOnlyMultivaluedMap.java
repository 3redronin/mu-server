package io.muserver.rest;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        throw new NotImplementedException("Invalid access for readonly map");
    }

    public void add(K key, V value) {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    @SafeVarargs
    public final void addAll(K key, V... newValues) {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    public void addAll(K key, List<V> valueList) {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    public V getFirst(K key) {
        return actual.getFirst(key);
    }

    public void addFirst(K key, V value) {
        actual.addFirst(key, value);
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
        return actual.values();
    }

    public int size() {
        return actual.size();
    }

    public List<V> remove(Object key) {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    public void putAll(Map<? extends K, ? extends List<V>> m) {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    public List<V> put(K key, List<V> value) {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    public Set<K> keySet() {
        return actual.keySet();
    }

    public boolean isEmpty() {
        return actual.isEmpty();
    }

    public List<V> get(Object key) {
        return actual.get(key);
    }

    public Set<Entry<K, List<V>>> entrySet() {
        return actual.entrySet();
    }

    public boolean containsValue(Object value) {
        return actual.containsValue(value);
    }

    public boolean containsKey(Object key) {
        return actual.containsKey(key);
    }

    public void clear() {
        throw new NotImplementedException("Invalid access for readonly map");
    }

    public boolean equalsIgnoreValueOrder(MultivaluedMap<K, V> omap) {
        return actual.equalsIgnoreValueOrder(omap);
    }
}
