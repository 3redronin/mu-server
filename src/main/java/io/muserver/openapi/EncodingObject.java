package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.openapi.ParameterObject.actualValue;
import static io.muserver.openapi.ParameterObject.allowedStyles;

/**
 * @see EncodingObjectBuilder
 */
public class EncodingObject implements JsonWriter {

    private final @Nullable String contentType;
    private final @Nullable Map<String, HeaderObject> headers;
    private final @Nullable String style;
    private final @Nullable Boolean explode;
    private final @Nullable Boolean allowReserved;

    EncodingObject(@Nullable String contentType, @Nullable Map<String, HeaderObject> headers, @Nullable String style, @Nullable Boolean explode, @Nullable Boolean allowReserved) {
        if (style != null && !allowedStyles().contains(style)) {
            throw new IllegalArgumentException("'style' must be one of " + allowedStyles() + " but was " + style);
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

    /**
     * @return The value described by {@link EncodingObjectBuilder#withContentType}
     */
    public @Nullable String contentType() {
        return contentType;
    }

    /**
     * @return The value described by {@link EncodingObjectBuilder#withHeaders}
     */
    public @Nullable Map<String, HeaderObject> headers() {
        return headers;
    }

    /**
     * @return The value described by {@link EncodingObjectBuilder#withStyle}
     */
    public @Nullable String style() {
        return style;
    }

    /**
     * @return The value described by {@link EncodingObjectBuilder#withExplode}
     */
    public boolean explode() {
        return actualValue(explode, style == null || "form".equals(style));
    }

    /**
     * @return The value described by {@link EncodingObjectBuilder#withAllowReserved}
     */
    public boolean allowReserved() {
        return actualValue(allowReserved, false);
    }
}
