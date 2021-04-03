package io.muserver.rest;

import javax.ws.rs.core.AbstractMultivaluedMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Lowercase, because headers should be lowercase
 * @param <V> The value type (the key type is String)
 */
class LowercasedMultivaluedHashMap<V> extends AbstractMultivaluedMap<String, V> {

    LowercasedMultivaluedHashMap() {
        super(new LowercasedHashMap<>());
    }

    /**
     * Lowercase, because headers should be lowercase
     * @param <V> The value type (the key type is String)
     */
    private static class LowercasedHashMap<V> extends HashMap<String, V> {
        @Override
        public V get(Object key) {
            return super.get(toLower(key));
        }

        @Override
        public boolean containsKey(Object key) {
            return super.containsKey(toLower(key));
        }

        @Override
        public V put(String key, V value) {
            return super.put(key.toLowerCase(), value);
        }

        @Override
        public void putAll(Map<? extends String, ? extends V> m) {
            for (Entry<? extends String, ? extends V> entry : m.entrySet()) {
                put(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public V remove(Object key) {
            return super.remove(toLower(key));
        }

        @Override
        public V getOrDefault(Object key, V defaultValue) {
            return super.getOrDefault(toLower(key), defaultValue);
        }

        @Override
        public V putIfAbsent(String key, V value) {
            return super.putIfAbsent(key.toLowerCase(), value);
        }

        @Override
        public boolean remove(Object key, Object value) {
            return super.remove(toLower(key), value);
        }

        @Override
        public boolean replace(String key, V oldValue, V newValue) {
            return super.replace(key.toLowerCase(), oldValue, newValue);
        }

        @Override
        public V replace(String key, V value) {
            return super.replace(key.toLowerCase(), value);
        }

        @Override
        public V computeIfAbsent(String key, Function<? super String, ? extends V> mappingFunction) {
            return super.computeIfAbsent(key.toLowerCase(), mappingFunction);
        }

        @Override
        public V computeIfPresent(String key, BiFunction<? super String, ? super V, ? extends V> remappingFunction) {
            return super.computeIfPresent(key.toLowerCase(), remappingFunction);
        }

        @Override
        public V compute(String key, BiFunction<? super String, ? super V, ? extends V> remappingFunction) {
            return super.compute(key.toLowerCase(), remappingFunction);
        }

        @Override
        public V merge(String key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            return super.merge(key.toLowerCase(), value, remappingFunction);
        }

        private static Object toLower(Object val) {
            if (val instanceof String) {
                return ((String) val).toLowerCase();
            }
            return val;
        }
    }
}
