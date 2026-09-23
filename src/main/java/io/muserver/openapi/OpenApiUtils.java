package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.*;

class OpenApiUtils {

    private OpenApiUtils() {}

    @SuppressWarnings("unchecked")
    static <K, V> @Nullable Map<K, V> immutable(@Nullable Map<K, V> map) {
        if (map == null) {
            return null;
        }
        return (Map<K, V>) JsonValues.freeze(map);
    }

    @SuppressWarnings("unchecked")
    static <T> @Nullable List<T> immutable(@Nullable List<T> list) {
        if (list == null) {
            return null;
        }
        return (List<T>) JsonValues.freeze(list);
    }
}
