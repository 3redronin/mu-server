package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

class Jsonizer {
    private Jsonizer() {}

    private static String jsonEncode(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            ;
    }

    static boolean append(Writer writer, String key, Object value, boolean isFirst) throws IOException {
        if (value == null) {
            return isFirst;
        }
        if (!isFirst) {
            writer.append(',');
        }
        writer.append('"').append(jsonEncode(key)).append("\":");
        appendValue(writer, value);
        return false;
    }

    private static void appendValue(Writer writer, Object value) throws IOException {
        if (value instanceof JsonWriter) {
            ((JsonWriter) value).writeJson(writer);
        } else if (value instanceof List) {
            List list = (List)value;
            writer.append('[');
            boolean isFirst = true;
            for (Object obj : list) {
                if (!isFirst) {
                    writer.append(',');
                }
                appendValue(writer, obj);
                isFirst = false;
            }
            writer.append(']');
        } else if (value instanceof Map) {
            writer.append('{');
            @SuppressWarnings("unchecked")
            Map<String,?> map = (Map<String,?>)value;
            boolean isFirst = true;
            for (Map.Entry<String,?> entry : map.entrySet()) {
                isFirst = append(writer, entry.getKey(), entry.getValue(), isFirst);
            }
            writer.append('}');
        } else {
            if (value instanceof Number || value instanceof Boolean) {
                writer.append(value.toString());
            } else {
                writer.append('"').append(jsonEncode(value.toString())).append('"');
            }
        }
    }
}
