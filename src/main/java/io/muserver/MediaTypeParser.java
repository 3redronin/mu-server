package io.muserver;

import javax.ws.rs.core.MediaType;
import java.util.List;

import static io.muserver.Mutils.notNull;

/**
 * A utility class to parse Media Type or Content Type values such as <code>text/plain</code> and <code>text/plain; charset=UTF-8</code> etc
 */
class MediaTypeParser {

    /**
     * Converts a string such as "text/plain" into a MediaType object.
     * @param value The value to parse
     * @return A MediaType object
     */
    public static MediaType fromString(String value) {
        if (value == null) {
            throw new NullPointerException("value");
        }
        List<HeaderValue> headerValues = HeaderValue.fromString(value);
        if (headerValues.isEmpty()) {
            throw new IllegalArgumentException("The value '" + value + "' did not contain a valid header value");
        }
        HeaderValue v = headerValues.get(0);
        String[] split = v.value().split("/", 2);
        String type = split[0];
        String subType = split.length == 1 ? MediaType.MEDIA_TYPE_WILDCARD : split[1];
        return new MediaType(type, subType, v.parameters());
    }

    /**
     * Converts a MediaType object into a string, suitable for printing in an HTTP header.
     * @param mediaType The type to print
     * @return A String, such as "image/jpeg"
     */
    public static String toString(MediaType mediaType) {
        notNull("mediaType", mediaType);
        return new HeaderValue(mediaType.getType() + "/" + mediaType.getSubtype(), mediaType.getParameters()).toString();
    }
}
