package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.*;

class OpenApiUtils {

    private OpenApiUtils() {}

    static <K, V> @Nullable Map<K, V> immutable(@Nullable Map<K, V> map) {
        if (map == null) {
            return null;
        }
        return Collections.unmodifiableMap(new HashMap<>(map));
    }

    static <T> @Nullable List<T> immutable(@Nullable List<T> list) {
        if (list == null) {
            return null;
        }
        return Collections.unmodifiableList(new ArrayList<>(list));
    }
}
