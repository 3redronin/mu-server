package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.lang.reflect.Array;
import java.util.Map;

/**
 * Internal JSON serialization helper. Not considered part of MuServer's official API so use at own risk.
 */
public class Jsonizer {
    private Jsonizer() {
    }

    private static String jsonEncode(String value) {
        StringBuilder encoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                encoded.append('\\').append(c);
            } else if (c == '\n') { encoded.append("\\n");
            } else if (c == '\r') { encoded.append("\\r");
            } else if (c == '\t') { encoded.append("\\t");
            } else if (c == '\b') { encoded.append("\\b");
            } else if (c == '\f') { encoded.append("\\f");
            } else if (c < 0x20) {
                encoded.append("\\u00").append(Character.forDigit(c >>> 4, 16))
                    .append(Character.forDigit(c & 15, 16));
            } else {
                encoded.append(c);
            }
        }
        return encoded.toString();
    }

    static boolean append(Writer writer, String key, @Nullable Object value, boolean isFirst) throws IOException {
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
     *
     * @param writer The writer to write to
     *
     * @param values The values to write
     *
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
     *
     * @param writer The writer to write to
     *
     * @param value The value to write
     *
     * @throws IOException Thrown if the writer throws this while writing
     */
    public static void writeValue(Writer writer, @Nullable Object value) throws IOException {
        if (value == null || value == JsonNull.INSTANCE) {
            writer.append("null");
        } else if (value instanceof JsonWriter) {
            ((JsonWriter) value).writeJson(writer);
        } else if (value instanceof Collection) {
            Collection<?> list = (Collection<?>) value;
            writer.append('[');
            boolean isFirst = true;
            for (@Nullable Object obj : list) {
                if (!isFirst) {
                    writer.append(',');
                }
                writeValue(writer, obj);
                isFirst = false;
            }
            writer.append(']');
        } else if (value.getClass().isArray()) {
            writer.append('[');
            for (int i = 0; i < Array.getLength(value); i++) {
                if (i > 0) writer.append(',');
                writeValue(writer, Array.get(value, i));
            }
            writer.append(']');
        } else if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, ?> map = (Map<String, ?>) value;
            writeObject(writer, map);
        } else {
            if (value instanceof Number || value instanceof Boolean) {
                if ((value instanceof Double && !Double.isFinite((Double) value))
                    || (value instanceof Float && !Float.isFinite((Float) value))) {
                    throw new IllegalArgumentException("JSON numbers must be finite: " + value);
                }
                writer.append(value.toString());
            } else {
                // TODO: use param converters
                String valueAsString = value instanceof Enum
                    ? ((Enum<? extends Enum<?>>) value).name()
                    : value.toString();
                writer.append('"').append(jsonEncode(valueAsString)).append('"');
            }
        }
    }
}
