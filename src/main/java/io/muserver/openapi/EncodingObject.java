package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.openapi.ParameterObject.allowedStyles;

/**
 * @see EncodingObjectBuilder
 */
public class EncodingObject implements JsonWriter {

    /**
     * @deprecated Use {@link #contentType()} instead
     */
    @Deprecated
    public final String contentType;
    /**
      @deprecated Use {@link #headers()} instead
     */
    @Deprecated
    public final Map<String, HeaderObject> headers;
    /**
      @deprecated Use {@link #style()} instead
     */
    @Deprecated
    public final String style;
    /**
      @deprecated Use {@link #explode()} instead
     */
    @Deprecated
    public final boolean explode;
    /**
      @deprecated Use {@link #allowReserved()} instead
     */
    @Deprecated
    public final boolean allowReserved;

    EncodingObject(String contentType, Map<String, HeaderObject> headers, String style, boolean explode, boolean allowReserved) {
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
    public String contentType() {
        return contentType;
    }

    /**
     * @return The value described by {@link EncodingObjectBuilder#withHeaders}
     */
    public Map<String, HeaderObject> headers() {
        return headers;
    }

    /**
     * @return The value described by {@link EncodingObjectBuilder#withStyle}
     */
    public String style() {
        return style;
    }

    /**
     * @return The value described by {@link EncodingObjectBuilder#withExplode}
     */
    public boolean explode() {
        return explode;
    }

    /**
     * @return The value described by {@link EncodingObjectBuilder#withAllowReserved}
     */
    public boolean allowReserved() {
        return allowReserved;
    }
}
