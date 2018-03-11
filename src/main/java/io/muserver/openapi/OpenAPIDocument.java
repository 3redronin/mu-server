package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import static io.muserver.Mutils.notNull;

public class OpenAPIDocument implements JsonWriter {
    public final String openapi = "3.0.1";
    public final Info info;
    public final List<Server> servers;

    public OpenAPIDocument(Info info, List<Server> servers) {
        notNull("info", info);
        notNull("servers", servers);
        this.info = info;
        this.servers = servers;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write("{");
        boolean isFirst = true;
        isFirst = !append(writer, "openapi", openapi, isFirst);
        isFirst = !append(writer, "info", info, isFirst);
        isFirst = !append(writer, "servers", servers, isFirst);
        writer.write("}");
    }

    private static String jsonEncode(String value) {
        return value
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\"", "\\\"")
            .replace("\\", "\\\\")
            ;
    }

    static boolean append(Writer writer, String key, Object value, boolean isFirst) throws IOException {
        if (value == null) {
            return false;
        }
        if (!isFirst) {
            writer.append(',');
        }
        writer.append("\"").append(jsonEncode(key)).append("\":");
        appendValue(writer, value);
        return true;
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
                isFirst = !append(writer, entry.getKey(), entry.getValue(), isFirst);
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
