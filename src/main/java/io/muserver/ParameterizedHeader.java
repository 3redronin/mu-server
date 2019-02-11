package io.muserver;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static io.muserver.Mutils.notNull;
import static io.muserver.ParameterizedHeaderWithValue.isOWS;
import static io.muserver.ParameterizedHeaderWithValue.isTChar;
import static java.util.Collections.emptyMap;

/**
 * <p>A utility class to parse headers that are of the format <code>param1, param2=value, param3="quoted string"</code>
 * such as Cache-Controlg etc.</p>
 */
public class ParameterizedHeader {

    private final Map<String, String> parameters;

    /**
     * Creates a value with parameters
     *
     * @param parameters A map of parameters, such as <code>charset: UTF-8</code>
     */
    ParameterizedHeader(Map<String, String> parameters) {
        notNull("parameters", parameters);
        this.parameters = parameters;
    }

    public Map<String, String> parameters() {
        return parameters;
    }

    private enum State {PARAM_NAME, PARAM_VALUE}

    /**
     * Converts headers that are values followed by optional parameters
     *
     * @param input The value to parse
     * @return An object containing a map of name/value pairs (where values may be null)
     */
    public static ParameterizedHeader fromString(String input) {
        if (input == null || input.length() == 0) {
            return new ParameterizedHeader(emptyMap());
        }
        StringBuilder buffer = new StringBuilder();

        Map<String, String> parameters = new HashMap<>();
        State state = State.PARAM_NAME;
        String paramName = null;
        boolean isQuotedString = false;

        headerValueLoop:
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (state == State.PARAM_NAME) {
                if (c == ',') {
                    if (buffer.length() > 0) {
                        parameters.put(buffer.toString(), null);
                        buffer.setLength(0);
                        paramName = null;
                    }
                } else if (c == '=') {
                    paramName = buffer.toString();
                    if (paramName.isEmpty()) {
                        throw new IllegalStateException("Nameless values not allowed");
                    }
                    buffer.setLength(0);
                    state = State.PARAM_VALUE;
                } else if (isTChar(c)) {
                    buffer.append(c);
                } else if (isOWS(c)) {
                    // ignore it
                } else {
                    throw new IllegalStateException("Got ascii " + ((int) c) + " while in " + state);
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
                        } else if (c == ',') {
                            parameters.put(paramName, buffer.toString());
                            buffer.setLength(0);
                            paramName = null;
                            state = State.PARAM_NAME;
                        } else if (isOWS(c)) {
                            // ignore it
                        } else {
                            throw new IllegalArgumentException("Got character code " + ((int) c) + " (" + c + ") while parsing parameter value");
                        }
                    }
                }
            }
        }
        if (state == State.PARAM_VALUE) {
            if (parameters == null) {
                parameters = new HashMap<>();
            }
            parameters.put(paramName, buffer.toString());
            buffer.setLength(0);
        } else {
            if (buffer.length() > 0) {
                parameters.put(buffer.toString(), null);
            }
        }

        return new ParameterizedHeader(parameters);
    }

    /**
     * Converts the HeaderValue into a string, suitable for printing in an HTTP header.
     *
     * @return A String, such as "some-value" or "content-type:text/html;charset=UTF-8"
     */
    public String toString() {
        StringBuilder sb = new StringBuilder();
        Map<String, String> parameters = this.parameters();
        for (Map.Entry<String, String> entry : parameters.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey());
            String value = entry.getValue();
            if (value != null) {
                boolean needsQuoting = false;
                for (int i = 0; i < value.length(); i++) {
                    if (!isTChar(value.charAt(i))) {
                        needsQuoting = true;
                        break;
                    }
                }
                String v = needsQuoting ? '"' + value.replace("\"", "\\\"") + '"' : value;
                sb.append('=').append(v);
            }
        }
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ParameterizedHeader that = (ParameterizedHeader) o;
        return Objects.equals(parameters, that.parameters);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parameters);
    }


}