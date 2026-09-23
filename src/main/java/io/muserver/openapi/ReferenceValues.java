package io.muserver.openapi;

import org.jspecify.annotations.Nullable;
import java.util.*;

final class ReferenceValues {
    private ReferenceValues() { }
    static <T> @Nullable ReferenceOr<T> inline(@Nullable T value) { return value == null ? null : ReferenceOr.inline(value); }
    static <T> @Nullable T values(@Nullable ReferenceOr<T> value) { return value == null ? null : value.value(); }
    static <T> @Nullable Map<String, ReferenceOr<T>> inline(@Nullable Map<String, T> values) {
        if (values == null) return null;
        Map<String, ReferenceOr<T>> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, ReferenceOr.inline(value)));
        return result;
    }
    static <T> @Nullable List<ReferenceOr<T>> inline(@Nullable List<T> values) {
        if (values == null) return null;
        List<ReferenceOr<T>> result = new ArrayList<>();
        for (T value : values) result.add(ReferenceOr.inline(value));
        return result;
    }
    static <T> @Nullable Map<String, T> values(@Nullable Map<String, ReferenceOr<T>> values) {
        if (values == null) return null;
        Map<String, T> result = new LinkedHashMap<>();
        values.forEach((key, value) -> result.put(key, value.value()));
        return Collections.unmodifiableMap(result);
    }
    static <T> @Nullable List<T> values(@Nullable List<ReferenceOr<T>> values) {
        if (values == null) return null;
        List<T> result = new ArrayList<>();
        for (ReferenceOr<T> value : values) result.add(value.value());
        return Collections.unmodifiableList(result);
    }
}
