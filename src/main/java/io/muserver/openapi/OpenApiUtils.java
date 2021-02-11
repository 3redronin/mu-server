package io.muserver.openapi;

import java.util.*;

class OpenApiUtils {

    private OpenApiUtils() {}

    static <K, V> Map<K, V> immutable(Map<K, V> map) {
        return Collections.unmodifiableMap(new HashMap<>(map));
    }

    static <T> List<T> immutable(List<T> list) {
        return Collections.unmodifiableList(new ArrayList<>(list));
    }
}
