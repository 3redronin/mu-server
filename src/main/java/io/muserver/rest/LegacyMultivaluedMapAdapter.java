package io.muserver.rest;

import jakarta.ws.rs.core.MultivaluedHashMap;

import javax.ws.rs.core.MultivaluedMap;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

class LegacyMultivaluedMapAdapter<K, V> implements MultivaluedMap<K, V> {

    private final jakarta.ws.rs.core.MultivaluedMap<K, V> underylying;

    public LegacyMultivaluedMapAdapter(jakarta.ws.rs.core.MultivaluedMap<K, V> underylying) {
        this.underylying = underylying;
    }

    @Override
    public void putSingle(K key, V value) {
        underylying.putSingle(key, value);
    }

    @Override
    public void add(K key, V value) {
        underylying.add(key, value);
    }

    @Override
    public V getFirst(K key) {
        return underylying.getFirst(key);
    }

    @Override
    public void addAll(K key, V... newValues) {
        underylying.addAll(key, newValues);
    }

    @Override
    public void addAll(K key, List<V> valueList) {
        underylying.addAll(key, valueList);
    }

    @Override
    public void addFirst(K key, V value) {
        underylying.addFirst(key, value);
    }

    @Override
    public boolean equalsIgnoreValueOrder(MultivaluedMap<K, V> omap) {
        if (this == omap) {
            return true;
        }
        if (!keySet().equals(omap.keySet())) {
            return false;
        }
        for (Entry<K, List<V>> e : entrySet()) {
            List<V> olist = omap.get(e.getKey());
            if (e.getValue().size() != olist.size()) {
                return false;
            }
            for (V v : e.getValue()) {
                if (!olist.contains(v)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int size() {
        return underylying.size();
    }

    @Override
    public boolean isEmpty() {
        return underylying.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return underylying.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return underylying.containsValue(value);
    }

    @Override
    public List<V> get(Object key) {
        return underylying.get(key);
    }

    @Override
    public List<V> put(K key, List<V> value) {
        return underylying.put(key, value);
    }

    @Override
    public List<V> remove(Object key) {
        return underylying.remove(key);
    }

    @Override
    public void putAll(Map<? extends K, ? extends List<V>> m) {
        underylying.putAll(m);
    }

    @Override
    public void clear() {
        underylying.clear();
    }

    @Override
    public Set<K> keySet() {
        return underylying.keySet();
    }

    @Override
    public Collection<List<V>> values() {
        return underylying.values();
    }

    @Override
    public Set<Entry<K, List<V>>> entrySet() {
        return underylying.entrySet();
    }

    public static <K, V> jakarta.ws.rs.core.MultivaluedMap<K, V> toJakarta(MultivaluedMap<K, V> original) {
        jakarta.ws.rs.core.MultivaluedMap<K, V> copy = new MultivaluedHashMap<>();
        for (Entry<K, List<V>> entry : original.entrySet()) {
            copy.addAll(entry.getKey(), entry.getValue());
        }
        return copy;
    }
}
