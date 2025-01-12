package io.muserver;

import org.jspecify.annotations.Nullable;

import java.util.*;

import static io.muserver.Mutils.notNull;
import static io.muserver.ParseUtils.isOWS;
import static io.muserver.ParseUtils.isTChar;
import static java.util.Collections.emptyMap;

/**
 * <p>A utility class to parse headers that are of the format <code>param1, param2=value, param3="quoted string"</code>
 * such as Cache-Control etc.</p>
 * @see ParameterizedHeaderWithValue
 */
public class ParameterizedHeader {

    private final Map<String, @Nullable String> parameters;

    /**
     * Creates a value with parameters
     *
     * @param parameters A map of parameters, such as <code>charset: UTF-8</code>
     */
    private ParameterizedHeader(Map<String, @Nullable String> parameters) {
        notNull("parameters", parameters);
        this.parameters = parameters;
    }

    /**
     * @return Gets all the parameters
     */
    public Map<String, @Nullable String> parameters() {
        return parameters;
    }

    /**
     * @param name The name of the parameter to get
     * @return Gets a single parameter, or <code>null</code> if there is no value
     */
    public @Nullable String parameter(String name) {
        return parameters.get(name);
    }

    /**
     * @param name The name of the parameter to get
     * @param defaultValue The value to return if no parameter was set
     * @return Gets a single parameter, or the default value, if there is no value
     */
    public @Nullable String parameter(String name, @Nullable String defaultValue) {
        return parameters.getOrDefault(name, defaultValue);
    }

    /**
     * @param name The name of the parameter to look up
     * @return True if the parameter exists (with or without a value); otherwise false
     */
    public boolean hasParameter(String name) {
        return parameters.containsKey(name);
    }

    /**
     * @return Gets the parameters in the order declared (without the parameter values)
     */
    public List<String> parameterNames() {
        return new ArrayList<>(parameters.keySet());
    }

    private enum State {PARAM_NAME, PARAM_VALUE}

    /**
     * <p>Converts a comma-separated list of param names (with optional values) into a Parameterized Header</p>
     * <p>Null or blank strings return value with an empty parameter map.</p>
     * @param input The value to parse
     * @return An object containing a map of name/value pairs (where values may be null)
     * @throws IllegalArgumentException The value cannot be parsed
     */
    public static ParameterizedHeader fromString(@Nullable String input) {
        if (input == null || input.trim().isEmpty()) {
            return new ParameterizedHeader(emptyMap());
        }
        StringBuilder buffer = new StringBuilder();

        Map<String, @Nullable String> parameters = new LinkedHashMap<>(); // keeps insertion order
        State state = State.PARAM_NAME;
        String paramName = null;
        boolean isQuotedString = false;

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
                        throw new IllegalArgumentException("Nameless values not allowed");
                    }
                    buffer.setLength(0);
                    state = State.PARAM_VALUE;
                } else if (isTChar(c)) {
                    buffer.append(c);
                } else if (isOWS(c)) {
                    // ignore it
                } else {
                    throw new IllegalArgumentException("Got ascii " + ((int) c) + " while in " + state);
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
                        if (c == ',') {
                            parameters.put(paramName, buffer.toString());
                            buffer.setLength(0);
                            paramName = null;
                            state = State.PARAM_NAME;
                        } else if (isTChar(c)) {
                            buffer.append(c);
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
        Map<String, @Nullable String> parameters = this.parameters();
        for (Map.Entry<String, @Nullable String> entry : parameters.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey());
            String value = entry.getValue();
            if (value != null) {
                sb.append('=').append(ParseUtils.quoteIfNeeded(value));
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