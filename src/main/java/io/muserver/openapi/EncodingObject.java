package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.openapi.ParameterObject.allowedStyles;

/**
 * @see EncodingObjectBuilder
 */
public class EncodingObject implements JsonWriter {

    public final String contentType;
    public final Map<String, HeaderObject> headers;
    public final String style;
    public final boolean explode;
    public final boolean allowReserved;

    EncodingObject(String contentType, Map<String, HeaderObject> headers, String style, boolean explode, boolean allowReserved) {
        if (style != null && !allowedStyles.contains(style)) {
            throw new IllegalArgumentException("'style' must be one of " + allowedStyles + " but was " + style);
        }
        this.contentType = contentType;
        this.headers = headers;
        this.style = style;
        this.explode = explode;
        this.allowReserved = allowReserved;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.append('{');
        boolean isFirst = true;
        isFirst = Jsonizer.append(writer, "contentType", contentType, isFirst);
        isFirst = Jsonizer.append(writer, "headers", headers, isFirst);
        isFirst = Jsonizer.append(writer, "style", style, isFirst);
        isFirst = Jsonizer.append(writer, "explode", explode, isFirst);
        isFirst = Jsonizer.append(writer, "allowReserved", allowReserved, isFirst);
        writer.append('}');
    }
}
