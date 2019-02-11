package io.muserver;

import java.util.*;

import static io.muserver.Mutils.notNull;
import static java.util.Collections.emptyList;

/**
 * <p>A utility class to parse headers that are of the format <code>name; param1=value; param2="quoted string"</code>
 * such as Content-Type, Accepts, Content-Disposition etc.</p>
 * <p>More explicitly, a header that starts with a value, then has an optional list of semi-colon separated name/value pairs.</p>
 */
public class ParameterizedHeaderWithValue {

    private final String value;
    private final Map<String, String> parameters;

    /**
     * Creates a value with parameters
     * @param value The value such as <code>text/plain</code>
     * @param parameters A map of parameters, such as <code>charset: UTF-8</code>
     */
    ParameterizedHeaderWithValue(String value, Map<String, String> parameters) {
        notNull("value", value);
        notNull("parameters", parameters);
        this.value = value;
        this.parameters = parameters;
    }

    public String value() {
        return value;
    }

    public Map<String, String> parameters() {
        return parameters;
    }

    private enum State {VALUE, PARAM_NAME, PARAM_VALUE}

    /**
     * Converts headers that are values followed by optional parameters
     *
     * @param input The value to parse
     * @return A list of ParameterizedHeaderWithValue objects
     */
    public static List<ParameterizedHeaderWithValue> fromString(String input) {
        if (input == null || input.length() == 0) {
            return emptyList();
        }
        StringBuilder buffer = new StringBuilder();

        List<ParameterizedHeaderWithValue> results = new ArrayList<>();

        int i = 0;
        while (i < input.length()) {

            String value = null;
            Map<String, String> parameters = null;
            State state = State.VALUE;
            String paramName = null;
            boolean isQuotedString = false;

            headerValueLoop:
            for (; i < input.length(); i++) {
                char c = input.charAt(i);

                if (state == State.VALUE) {
                    if (c == ';') {
                        value = buffer.toString().trim();
                        buffer.setLength(0);
                        state = State.PARAM_NAME;
                    } else if (c == ',') {
                        i++;
                        break headerValueLoop;
                    } else if (isVChar(c) || isOWS(c)) {
                        buffer.append(c);
                    } else {
                        throw new IllegalStateException("Got ascii " + ((int) c) + " while in " + state);
                    }
                } else if (state == State.PARAM_NAME) {
                    if (c == ',' && buffer.length() == 0) {
                        i++; // a semi-colon without an parameter, like "something;"
                        break headerValueLoop;
                    } else if (c == '=') {
                        paramName = buffer.toString();
                        buffer.setLength(0);
                        state = State.PARAM_VALUE;
                    } else if (isTChar(c)) {
                        buffer.append(c);
                    } else if (isOWS(c)) {
                        if (buffer.length() > 0) {
                            throw new IllegalStateException("Got whitespace in media type while in " + state + " - header was " + buffer);
                        }
                    } else {
                        throw new IllegalStateException("Got ascii " + ((int)c) + " while in " + state);
                    }
                } else {
                    boolean isFirst = !isQuotedString && buffer.length() == 0;
                    if (isFirst && isOWS(c)) {
                        // ignore it
                    } else if (isFirst && c == '"') {
                        isQuotedString = true;
                    } else {

                        if (isQuotedString) {
                            char lastChar = input.charAt(i - 1);
                            if (c == '\\') {
                                // don't append
                            } else if (lastChar == '\\') {
                                buffer.append(c);
                            } else if (c == '"') {
                                // this is the end, but we'll update on the next go
                                isQuotedString = false;
                            } else {
                                buffer.append(c);
                            }
                        } else {
                            if (isTChar(c)) {
                                buffer.append(c);
                            } else if (c == ';') {
                                if (parameters == null) {
                                    parameters = new HashMap<>();
                                }
                                parameters.put(paramName, buffer.toString());
                                buffer.setLength(0);
                                paramName = null;
                                state = State.PARAM_NAME;
                            } else if (isOWS(c)) {
                                // ignore it
                            } else if (c == ',') {
                                i++;
                                break headerValueLoop;
                            } else {
                                throw new IllegalArgumentException("Got character code " + ((int) c) + " (" + c + ") while parsing parameter value");
                            }
                        }
                    }
                }
            }
            switch (state) {
                case VALUE:
                    value = buffer.toString().trim();
                    buffer.setLength(0);
                    break;
                case PARAM_VALUE:
                    if (parameters == null) {
                        parameters = new HashMap<>();
                    }
                    parameters.put(paramName, buffer.toString());
                    buffer.setLength(0);
                    break;
                default:
                    if (buffer.length() > 0) {
                        throw new IllegalArgumentException("Unexpected ending point at state " + state + " for " + input);
                    }
            }

            results.add(new ParameterizedHeaderWithValue(value, parameters == null ? Collections.emptyMap() : parameters));
        }

        return results;
    }

    /**
     * Converts the HeaderValue into a string, suitable for printing in an HTTP header.
     *
     * @return A String, such as "some-value" or "content-type:text/html;charset=UTF-8"
     */
    public String toString() {
        StringBuilder sb = new StringBuilder(this.value());
        Map<String, String> parameters = this.parameters();
        parameters.forEach((key, value) -> {
            boolean needsQuoting = false;
            for (int i = 0; i < value.length(); i++) {
                if (!isTChar(value.charAt(i))) {
                    needsQuoting = true;
                    break;
                }
            }
            String v = needsQuoting ? '"' + value.replace("\"", "\\\"") + '"' : value;
            sb.append(';').append(key).append('=').append(v);
        });
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParameterizedHeaderWithValue that = (ParameterizedHeaderWithValue) o;
        return Objects.equals(value, that.value) &&
            Objects.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, parameters);
    }

    static boolean isTChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9' || c == '!' ||
            c == '#' || c == '$' || c == '%' || c == '&' || c == '\'' || c == '*' || c == '+' ||
            c == '-' || c == '.' || c == '^' || c == '_' || c == '`' || c == '|' || c == '~');
    }
    static boolean isVChar(char c) {
        return c >= 0x21 && c <= 0x7E;
    }
    static boolean isOWS(char c) {
        return c == ' ' || c == '\t';
    }
}