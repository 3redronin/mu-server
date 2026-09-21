package io.muserver.rest;

import io.muserver.openapi.*;
import org.jspecify.annotations.Nullable;
import java.util.*;

/** Resolves document-local pointers for presentation only; never fetches external resources. */
final class DocumentationReferences {
    private final OpenAPIObject api;
    DocumentationReferences(OpenAPIObject api) { this.api = api; }

    <T> @Nullable T resolve(@Nullable ReferenceOr<T> value, Class<T> type) {
        return value == null ? null : resolveObject(value, type, new HashSet<>());
    }

    @Nullable SchemaObject schema(@Nullable SchemaObject schema) {
        return schema == null ? null : resolveObject(schema, SchemaObject.class, new HashSet<>());
    }

    @Nullable PathItemObject path(PathItemObject item) {
        return resolveObject(item, PathItemObject.class, new HashSet<>());
    }

    private <T> @Nullable T resolveObject(@Nullable Object value, Class<T> type, Set<String> visited) {
        String ref = null;
        if (value instanceof ReferenceOr) {
            ReferenceOr<?> wrapper = (ReferenceOr<?>) value;
            if (!wrapper.isReference()) return resolveObject(wrapper.value(), type, visited);
            ref = Objects.requireNonNull(wrapper.reference()).ref();
        } else if (value instanceof SchemaObject) ref = ((SchemaObject) value).ref();
        else if (value instanceof PathItemObject) ref = ((PathItemObject) value).ref();
        if (ref == null) return type.isInstance(value) ? type.cast(value) : null;
        if (!ref.startsWith("#/") && api.self() != null) {
            try {
                java.net.URI base = java.net.URI.create(api.self());
                java.net.URI resolved = base.resolve(ref);
                String document = resolved.toString().split("#", 2)[0];
                if (document.equals(base.toString().split("#", 2)[0]) && resolved.getRawFragment() != null) ref = "#" + resolved.getRawFragment();
            } catch (IllegalArgumentException ignored) { return null; }
        }
        if (!ref.startsWith("#/") || !visited.add(ref)) return null;
        Object target = api;
        for (String token : ref.substring(2).split("/", -1)) {
            target = property(target, token.replace("~1", "/").replace("~0", "~"));
            if (target == null) return null;
        }
        return resolveObject(target, type, visited);
    }

    private static @Nullable Object property(Object value, String name) {
        if (value instanceof ReferenceOr) {
            ReferenceOr<?> wrapper = (ReferenceOr<?>) value;
            if (wrapper.isReference()) return null;
            value = wrapper.value();
        }
        if (value instanceof Map) return ((Map<?, ?>) value).get(name);
        if (value instanceof List) {
            try { return ((List<?>) value).get(Integer.parseInt(name)); } catch (IndexOutOfBoundsException | NumberFormatException e) { return null; }
        }
        if (value instanceof SchemaObject) return ((SchemaObject) value).keywords().get(name);
        if (value instanceof PathsObject) {
            Map<String, PathItemObject> paths = ((PathsObject) value).pathItemObjects();
            return paths == null ? null : paths.get(name);
        }
        if (value instanceof ResponsesObject) {
            ResponsesObject responses = (ResponsesObject) value;
            return "default".equals(name) ? responses.defaultValueOrReferences() : responses.httpStatusCodesOrReferences().get(name);
        }
        if (value instanceof PathItemObject && Arrays.asList("get", "put", "post", "delete", "options", "head", "patch", "trace", "query").contains(name)) {
            Map<String, OperationObject> operations = ((PathItemObject) value).operations();
            return operations == null ? null : operations.get(name);
        }
        if (!value.getClass().getPackageName().equals("io.muserver.openapi")) return null;
        try {
            java.lang.reflect.Method accessor;
            try { accessor = value.getClass().getDeclaredMethod(name + "OrReferences"); }
            catch (NoSuchMethodException ignored) { accessor = value.getClass().getDeclaredMethod(name); }
            return accessor.invoke(value);
        } catch (ReflectiveOperationException e) { return null; }
    }
}
