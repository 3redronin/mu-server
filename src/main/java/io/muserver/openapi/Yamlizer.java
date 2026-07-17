package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class Yamlizer {
    private Yamlizer() {
    }

    static void writeJsonAsYaml(Writer writer, String json) throws IOException {
        @Nullable Object parsed = new Parser(json).parse();
        writeValue(writer, parsed, 0);
        writer.append('\n');
    }

    private static void writeValue(Writer writer, @Nullable Object value, int indent) throws IOException {
        if (value instanceof Map) {
            writeObject(writer, castMap(value), indent);
            return;
        }
        if (value instanceof List) {
            writeList(writer, castList(value), indent);
            return;
        }
        writeScalar(writer, value);
    }

    private static void writeObject(Writer writer, Map<String, @Nullable Object> map, int indent) throws IOException {
        if (map.isEmpty()) {
            writer.append("{}");
            return;
        }
        boolean first = true;
        for (Map.Entry<String, @Nullable Object> entry : map.entrySet()) {
            if (!first) {
                writer.append('\n');
            }
            first = false;
            appendIndent(writer, indent);
            writer.append(quoted(entry.getKey())).append(':');
            @Nullable Object value = entry.getValue();
            writeItem(writer, indent, value);
        }
    }

    private static void writeItem(Writer writer, int indent, @Nullable Object value) throws IOException {
        if (isContainer(value)) {
            if (isEmptyContainer(value)) {
                writer.append(' ');
                writeValue(writer, value, indent + 2);
                return;
            }
            writer.append('\n');
            writeValue(writer, value, indent + 2);
        } else {
            writer.append(' ');
            writeScalar(writer, value);
        }
    }

    private static void writeList(Writer writer, List<@Nullable Object> list, int indent) throws IOException {
        if (list.isEmpty()) {
            writer.append("[]");
            return;
        }
        boolean first = true;
        for (@Nullable Object item : list) {
            if (!first) {
                writer.append('\n');
            }
            first = false;
            appendIndent(writer, indent);
            writer.append('-');
            writeItem(writer, indent, item);
        }
    }

    private static void writeScalar(Writer writer, @Nullable Object value) throws IOException {
        if (value == null) {
            writer.append("null");
        } else if (value instanceof Boolean) {
            writer.append(value.toString());
        } else if (value instanceof NumberLiteral) {
            writer.append(((NumberLiteral) value).value);
        } else {
            writer.append(quoted(value.toString()));
        }
    }

    private static void appendIndent(Writer writer, int indent) throws IOException {
        for (int i = 0; i < indent; i++) {
            writer.append(' ');
        }
    }

    private static boolean isContainer(@Nullable Object value) {
        return value instanceof Map || value instanceof List;
    }

    private static boolean isEmptyContainer(@Nullable Object value) {
        if (value instanceof Map) {
            return ((Map<?, ?>) value).isEmpty();
        }
        if (value instanceof List) {
            return ((List<?>) value).isEmpty();
        }
        return false;
    }

    private static String quoted(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2);
        escaped.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
            }
        }
        escaped.append('"');
        return escaped.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, @Nullable Object> castMap(Object value) {
        return (Map<String, @Nullable Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<@Nullable Object> castList(Object value) {
        return (List<@Nullable Object>) value;
    }

    private static class NumberLiteral {
        final String value;

        NumberLiteral(String value) {
            this.value = value;
        }
    }

    private static class Parser {
        private final String json;
        private int index;

        Parser(String json) {
            this.json = json;
        }

        @Nullable Object parse() {
            skipWhitespace();
            @Nullable Object value = parseValue();
            skipWhitespace();
            if (index != json.length()) {
                throw error("Trailing characters after valid JSON");
            }
            return value;
        }

        private @Nullable Object parseValue() {
            skipWhitespace();
            if (index >= json.length()) {
                throw error("Unexpected end of JSON");
            }
            char c = json.charAt(index);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseList();
                case '"':
                    return parseString();
                case 't':
                    parseLiteral("true");
                    return Boolean.TRUE;
                case 'f':
                    parseLiteral("false");
                    return Boolean.FALSE;
                case 'n':
                    parseLiteral("null");
                    return null;
                default:
                    if (c == '-' || Character.isDigit(c)) {
                        return parseNumber();
                    }
                    throw error("Unexpected character while parsing value: " + c);
            }
        }

        private Map<String, @Nullable Object> parseObject() {
            expect('{');
            skipWhitespace();
            Map<String, @Nullable Object> map = new LinkedHashMap<>();
            if (peek('}')) {
                index++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, parseValue());
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return map;
                }
                expect(',');
            }
        }

        private List<@Nullable Object> parseList() {
            expect('[');
            skipWhitespace();
            List<@Nullable Object> list = new ArrayList<>();
            if (peek(']')) {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return list;
                }
                expect(',');
                skipWhitespace();
            }
        }

        private NumberLiteral parseNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            if (peek('0')) {
                index++;
            } else if (index < json.length() && Character.isDigit(json.charAt(index))) {
                while (index < json.length() && Character.isDigit(json.charAt(index))) {
                    index++;
                }
            } else {
                throw error("Invalid number");
            }

            if (peek('.')) {
                index++;
                if (index >= json.length() || !Character.isDigit(json.charAt(index))) {
                    throw error("Invalid number fraction");
                }
                while (index < json.length() && Character.isDigit(json.charAt(index))) {
                    index++;
                }
            }

            if (peek('e') || peek('E')) {
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                if (index >= json.length() || !Character.isDigit(json.charAt(index))) {
                    throw error("Invalid exponent");
                }
                while (index < json.length() && Character.isDigit(json.charAt(index))) {
                    index++;
                }
            }

            return new NumberLiteral(json.substring(start, index));
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (index < json.length()) {
                char c = json.charAt(index++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (index >= json.length()) {
                        throw error("Unexpected end of escape sequence");
                    }
                    char escaped = json.charAt(index++);
                    switch (escaped) {
                        case '"':
                        case '\\':
                        case '/':
                            sb.append(escaped);
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (index + 4 > json.length()) {
                                throw error("Invalid unicode escape");
                            }
                            String hex = json.substring(index, index + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                throw error("Invalid unicode escape: " + hex);
                            }
                            index += 4;
                            break;
                        default:
                            throw error("Invalid escape sequence: \\" + escaped);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("Unterminated string");
        }

        private void parseLiteral(String expected) {
            if (index + expected.length() > json.length() || !json.regionMatches(index, expected, 0, expected.length())) {
                throw error("Expected literal " + expected);
            }
            index += expected.length();
        }

        private boolean peek(char expected) {
            return index < json.length() && json.charAt(index) == expected;
        }

        private void expect(char expected) {
            if (!peek(expected)) {
                throw error("Expected '" + expected + "'");
            }
            index++;
        }

        private void skipWhitespace() {
            while (index < json.length() && Character.isWhitespace(json.charAt(index))) {
                index++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at index " + index);
        }
    }
}
