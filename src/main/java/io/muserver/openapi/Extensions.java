package io.muserver.openapi;

import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.io.Writer;
import java.util.*;

final class Extensions {
    private Extensions() { }
    static Map<String, Object> copy(@Nullable Map<String, Object> values) {
        if (values == null) return Collections.emptyMap();
        for (String key : values.keySet()) checkName(key);
        return JsonValues.freezeMap(values);
    }
    static void put(Map<String, Object> values, String name, @Nullable Object value) {
        checkName(name);
        if (value == null) values.remove(name); else values.put(name, value);
    }
    private static void checkName(String name) {
        if (!name.startsWith("x-")) throw new IllegalArgumentException("Extension names must start with x-: " + name);
    }
    static boolean write(Writer writer, Map<String, Object> values, boolean first) throws IOException {
        for (Map.Entry<String, Object> entry : values.entrySet()) first = Jsonizer.append(writer, entry.getKey(), entry.getValue(), first);
        return first;
    }
}
