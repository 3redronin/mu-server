package io.muserver;
import io.muserver.rest.MuRuntimeDelegate;

import javax.ws.rs.core.MediaType;
import java.util.List;

import static io.muserver.Mutils.notNull;

/**
 * A utility class to parse Media Type or Content Type values such as <code>text/plain</code> and <code>text/plain; charset=UTF-8</code> etc
 */
public class MediaTypeParser {
    static {
        MuRuntimeDelegate.ensureSet();
    }

    /**
     * Converts a string such as "text/plain" into a MediaType object.
     * @param value The value to parse
     * @return A MediaType object
     */
    public static MediaType fromString(String value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        List<ParameterizedHeaderWithValue> headerValues = ParameterizedHeaderWithValue.fromString(value);
        if (headerValues.isEmpty()) {
            throw new IllegalArgumentException("The value '" + value + "' did not contain a valid header value");
        }
        ParameterizedHeaderWithValue v = headerValues.get(0);
        String[] split = v.value().split("/");
        if (split.length != 2) {
            throw new IllegalArgumentException("Media types must be in the format 'type/subtype'; this is inavlid: '" + v.value() + "'");
        }
        return new MediaType(split[0], split[1], v.parameters());
    }

    /**
     * Converts a MediaType object into a string, suitable for printing in an HTTP header.
     * @param mediaType The type to print
     * @return A String, such as "image/jpeg"
     */
    public static String toString(MediaType mediaType) {
        notNull("mediaType", mediaType);
        return new ParameterizedHeaderWithValue(mediaType.getType() + "/" + mediaType.getSubtype(), mediaType.getParameters()).toString();
    }
}
