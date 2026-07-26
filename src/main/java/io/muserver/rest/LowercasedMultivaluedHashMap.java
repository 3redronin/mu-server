package io.muserver.rest;

import jakarta.ws.rs.core.AbstractMultivaluedMap;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
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
        public @Nullable V get(@Nullable Object key) {
            return super.get(toLower(key));
        }

        @Override
        public boolean containsKey(@Nullable Object key) {
            return super.containsKey(toLower(key));
        }

        @Override
        public @Nullable V put(String key, V value) {
            return super.put(key.toLowerCase(Locale.ROOT), value);
        }

        @Override
        public void putAll(Map<? extends String, ? extends V> m) {
            for (Entry<? extends String, ? extends V> entry : m.entrySet()) {
                put(entry.getKey(), entry.getValue());
            }
        }

        @Override
        public @Nullable V remove(@Nullable Object key) {
            return super.remove(toLower(key));
        }

        @Override
        public @Nullable V getOrDefault(@Nullable Object key, @Nullable V defaultValue) {
            return super.getOrDefault(toLower(key), defaultValue);
        }

        @Override
        public @Nullable V putIfAbsent(String key, V value) {
            return super.putIfAbsent(key.toLowerCase(Locale.ROOT), value);
        }

        @Override
        public boolean remove(@Nullable Object key, @Nullable Object value) {
            return super.remove(toLower(key), value);
        }

        @Override
        public boolean replace(String key, V oldValue, V newValue) {
            return super.replace(key.toLowerCase(Locale.ROOT), oldValue, newValue);
        }

        @Override
        public @Nullable V replace(String key, V value) {
            return super.replace(key.toLowerCase(Locale.ROOT), value);
        }

        @Override
        public @Nullable V computeIfAbsent(String key, Function<? super String, ? extends V> mappingFunction) {
            return super.computeIfAbsent(key.toLowerCase(Locale.ROOT), mappingFunction);
        }

        @Override
        public @Nullable V computeIfPresent(String key, BiFunction<? super String, ? super V, ? extends V> remappingFunction) {
            return super.computeIfPresent(key.toLowerCase(Locale.ROOT), remappingFunction);
        }

        @Override
        public @Nullable V compute(String key, BiFunction<? super String, ? super V, ? extends V> remappingFunction) {
            return super.compute(key.toLowerCase(Locale.ROOT), remappingFunction);
        }

        @Override
        public @Nullable V merge(String key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
            return super.merge(key.toLowerCase(Locale.ROOT), value, remappingFunction);
        }

        private static @Nullable Object toLower(@Nullable Object val) {
            if (val instanceof String) {
                return ((String) val).toLowerCase(Locale.ROOT);
            }
            return val;
        }
    }
}
