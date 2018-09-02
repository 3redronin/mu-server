package io.muserver;

import javax.ws.rs.core.MediaType;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.Parser.isOWS;
import static io.muserver.Parser.isTChar;

/**
 * A utility class to parse Media Type or Content Type values such as <code>text/plain</code> and <code>text/plain; charset=UTF-8</code> etc
 */
public class MediaTypeParser {

    private enum State { TYPE, SUB, PARAM_NAME, PARAM_VALUE}

    /**
     * Converts a string such as "text/plain" into a MediaType object.
     * @param value The value to parse
     * @return A MediaType object
     */
    public static MediaType fromString(String value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        StringBuilder buffer = new StringBuilder();
        String type = null;
        String subType = null;
        Map<String, String> parameters = null;
        State state = State.TYPE;
        String paramName = null;
        boolean isQuotedString = false;

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);


            if (state == State.TYPE) {
                if (isTChar(c)) {
                    buffer.append(c);
                } else if (c == '/') {
                    type = buffer.toString();
                    buffer.setLength(0);
                    state = State.SUB;
                } else if (Parser.isOWS(c)) {
                    if (buffer.length() > 0) {
                        throw new IllegalStateException("Got whitespace in media type while in " + state + " - header was " + buffer);
                    }
                } else {
                    throw new IllegalStateException("Got ascii " + c + " while in " + state);
                }
            } else if (state == State.SUB) {
                if (isTChar(c)) {
                    buffer.append(c);
                } else if (c == ';') {
                    subType = buffer.toString();
                    buffer.setLength(0);
                    state = State.PARAM_NAME;
                } else if (Parser.isOWS(c)) {
                    // just ignore
                } else {
                    throw new IllegalStateException("Got ascii " + ((int)c) + " while in " + state);
                }
            } else if (state == State.PARAM_NAME) {
                if (isTChar(c)) {
                    buffer.append(c);
                } else if (c == '=') {
                    paramName = buffer.toString();
                    buffer.setLength(0);
                    state = State.PARAM_VALUE;
                } else if (Parser.isOWS(c)) {
                    if (buffer.length() > 0) {
                        throw new IllegalStateException("Got whitespace in media type while in " + state + " - header was " + buffer);
                    }
                } else {
                    throw new IllegalStateException("Got ascii " + c + " while in " + state);
                }
            } else if (state == State.PARAM_VALUE) {
                boolean isFirst = !isQuotedString && buffer.length() == 0;
                if (isFirst && isOWS(c)) {
                    // ignore it
                } else if (isFirst && c == '"') {
                    isQuotedString = true;
                } else {

                    if (isQuotedString) {
                        char lastChar = value.charAt(i - 1);
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
                        } else {
                            throw new IllegalArgumentException("Got character code " + ((int)c) + " while parsing parameter value");
                        }
                    }
                }
            }
        }
        switch (state) {
            case TYPE:
                type = buffer.toString();
                break;
            case SUB:
                subType = buffer.toString();
                break;
            case PARAM_VALUE:
                if (parameters == null) {
                    parameters = new HashMap<>();
                }
                parameters.put(paramName, buffer.toString());
                break;
            default:
                if (buffer.length() > 0) {
                    throw new IllegalArgumentException("Unexpected ending point at state " + state + " for " + value);
                }
        }

        return new MediaType(type, subType, parameters == null ? Collections.emptyMap() : parameters);
    }

    /**
     * Converts a MediaType object into a string, suitable for printing in an HTTP header.
     * @param mediaType The type to print
     * @return A String, such as "image/jpeg"
     */
    public static String toString(MediaType mediaType) {
        notNull("mediaType", mediaType);
        StringBuilder sb = new StringBuilder(mediaType.getType() + "/" + mediaType.getSubtype());
        Map<String, String> parameters = mediaType.getParameters();
        if (parameters != null) {
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
        }
        return sb.toString();
    }
}
