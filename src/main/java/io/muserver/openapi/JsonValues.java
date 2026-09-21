package io.muserver.openapi;

import org.jspecify.annotations.Nullable;
import java.lang.reflect.Array;
import java.util.*;

/** Shared immutable JSON snapshots and schema validation. */
final class JsonValues {
    private JsonValues() { }

    static Object freeze(@Nullable Object value) {
        if (value == null || value == JsonNull.INSTANCE) return JsonNull.INSTANCE;
        if (value instanceof Map) {
            Map<String, Object> copy = new TreeMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new IllegalArgumentException("JSON object keys must be strings");
                copy.put((String) entry.getKey(), freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection || value.getClass().isArray()) {
            List<Object> copy = new ArrayList<>();
            if (value instanceof Collection) {
                for (Object item : (Collection<?>) value) copy.add(freeze(item));
            } else {
                for (int i = 0; i < Array.getLength(value); i++) copy.add(freeze(Array.get(value, i)));
            }
            return Collections.unmodifiableList(copy);
        }
        if ((value instanceof Double && !Double.isFinite((Double) value))
            || (value instanceof Float && !Float.isFinite((Float) value))) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        if (value instanceof Number) {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal) return value;
            return new java.math.BigDecimal(value.toString());
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Enum || value instanceof JsonWriter) return value;
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> freezeMap(Map<String, Object> values) {
        return (Map<String, Object>) freeze(values);
    }

    static @Nullable Double doubleValue(@Nullable Object value) {
        return value == null ? null : ((Number) value).doubleValue();
    }

    @SuppressWarnings("unchecked")
    static List<String> types(@Nullable Object value) {
        return value instanceof String ? Collections.singletonList((String) value)
            : value == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>((List<String>) value));
    }

    static @Nullable String singleType(@Nullable Object value) {
        List<String> types = types(value);
        if (types.size() > 1) throw new IllegalStateException("This schema has a type union; use types() instead of type()");
        return types.isEmpty() ? null : types.get(0);
    }

    static void validateSchema(Map<String, Object> values) {
        if (values.containsKey("type")) {
            List<String> types = types(values.get("type"));
            if (types.isEmpty() || new HashSet<>(types).size() != types.size()
                || !Arrays.asList("null", "boolean", "object", "array", "number", "integer", "string").containsAll(types)) {
                throw new IllegalArgumentException("type must contain distinct JSON Schema types");
            }
        }
        for (String key : Arrays.asList("maxLength", "minLength", "maxItems", "minItems", "maxProperties", "minProperties", "minContains", "maxContains")) {
            Object value = values.get(key);
            if (value != null && (!(value instanceof Number) || new java.math.BigDecimal(value.toString()).signum() < 0
                || new java.math.BigDecimal(value.toString()).stripTrailingZeros().scale() > 0)) {
                throw new IllegalArgumentException(key + " must be a non-negative integer");
            }
        }
        for (String key : Arrays.asList("maximum", "minimum", "exclusiveMaximum", "exclusiveMinimum", "multipleOf")) {
            Object value = values.get(key);
            if (value != null && !(value instanceof Number)) throw new IllegalArgumentException(key + " must be numeric");
        }
        Object multiple = values.get("multipleOf");
        if (multiple != null && new java.math.BigDecimal(multiple.toString()).signum() <= 0) {
            throw new IllegalArgumentException("multipleOf must be positive");
        }
        for (String key : Arrays.asList("allOf", "oneOf", "anyOf")) {
            Object value = values.get(key);
            if (value instanceof List && ((List<?>) value).isEmpty()) throw new IllegalArgumentException(key + " must not be empty");
        }
    }
}
