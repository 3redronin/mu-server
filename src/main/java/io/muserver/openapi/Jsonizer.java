package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

/**
 * Internal JSON serialization helper. Not considered part of MuServer's official API so use at own risk.
 */
public class Jsonizer {
    private Jsonizer() {
    }

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
        writeValue(writer, value);
        return false;
    }

    /**
     * Writes a JSON object from the supplied map.
     * <p>This is an internal helper and is intentionally minimal.</p>
     * @param writer The writer to write to
     * @param values The values to write
     * @throws IOException Thrown if the writer throws this while writing
     */
    public static void writeObject(Writer writer, Map<String, ?> values) throws IOException {
        writer.append('{');
        boolean isFirst = true;
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (!isFirst) {
                writer.append(',');
            }
            writer.append('"').append(jsonEncode(entry.getKey())).append("\":");
            writeValue(writer, entry.getValue());
            isFirst = false;
        }
        writer.append('}');
    }

    /**
     * Writes a JSON value.
     * <p>This is an internal helper and is intentionally minimal.</p>
     * @param writer The writer to write to
     * @param value The value to write
     * @throws IOException Thrown if the writer throws this while writing
     */
    public static void writeValue(Writer writer, Object value) throws IOException {
        if (value == null) {
            writer.append("null");
        } else if (value instanceof JsonWriter) {
            ((JsonWriter) value).writeJson(writer);
        } else if (value instanceof List) {
            List list = (List) value;
            writer.append('[');
            boolean isFirst = true;
            for (Object obj : list) {
                if (!isFirst) {
                    writer.append(',');
                }
                writeValue(writer, obj);
                isFirst = false;
            }
            writer.append(']');
        } else if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, ?> map = (Map<String, ?>) value;
            writeObject(writer, map);
        } else {
            if (value instanceof Number || value instanceof Boolean) {
                writer.append(value.toString());
            } else {
                // TODO: use param converters
                String valueAsString = value.getClass().isEnum()
                    ? ((Enum<? extends Enum<?>>) value).name()
                    : value.toString();
                writer.append('"').append(jsonEncode(valueAsString)).append('"');
            }
        }
    }
}
