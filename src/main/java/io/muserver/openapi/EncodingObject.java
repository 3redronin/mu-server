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
    private final @Nullable Map<String, EncodingObject> encoding;
    private final java.util.@Nullable List<EncodingObject> prefixEncoding;
    private final @Nullable EncodingObject itemEncoding;
    private final Map<String, Object> extensions;

    private final @Nullable String contentType;
    private final @Nullable Map<String, ReferenceOr<HeaderObject>> headers;
    private final @Nullable String style;
    private final @Nullable Boolean explode;
    private final @Nullable Boolean allowReserved;

    EncodingObject(@Nullable String contentType, @Nullable Map<String, ReferenceOr<HeaderObject>> headers, @Nullable String style, @Nullable Boolean explode, @Nullable Boolean allowReserved, @Nullable Map<String, EncodingObject> encoding, java.util.@Nullable List<EncodingObject> prefixEncoding, @Nullable EncodingObject itemEncoding, @Nullable Map<String, Object> extensions) {
        this.encoding = OpenApiUtils.immutable(encoding);
        this.prefixEncoding = OpenApiUtils.immutable(prefixEncoding);
        this.itemEncoding = itemEncoding;
        if (encoding != null && (prefixEncoding != null || itemEncoding != null)) throw new IllegalArgumentException("encoding cannot be combined with prefixEncoding or itemEncoding");
        this.extensions = Extensions.copy(extensions);
        if (style != null && !ParameterObject.validStyle("query", style)) {
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
        isFirst = Jsonizer.append(writer, "encoding", encoding, isFirst);
        isFirst = Jsonizer.append(writer, "prefixEncoding", prefixEncoding, isFirst);
        isFirst = Jsonizer.append(writer, "itemEncoding", itemEncoding, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
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
        return ReferenceValues.values(headers);
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
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return inline values and references for headers */
    public @Nullable Map<String, ReferenceOr<HeaderObject>> headersOrReferences() { return headers; }
    /** @return a builder preserving all fields and extensions */
    public EncodingObjectBuilder toBuilder() {
        return new EncodingObjectBuilder()
            .withEncoding(encoding).withPrefixEncoding(prefixEncoding).withItemEncoding(itemEncoding).withExtensions(extensions).withContentType(contentType).withHeadersOrReferences(headers).withStyle(style).withExplode(explode).withAllowReserved(allowReserved);
    }
    /**
     * @return the nested encodings keyed by property name, or null when omitted
     * @see EncodingObjectBuilder#withEncoding
     */
    public @Nullable Map<String, EncodingObject> encoding() { return encoding; }
    /**
     * @return the encodings for the initial nested multipart parts, in order, or null when omitted
     * @see EncodingObjectBuilder#withPrefixEncoding
     */
    public java.util.@Nullable List<EncodingObject> prefixEncoding() { return prefixEncoding; }
    /**
     * @return the encoding for remaining nested multipart parts, or null when omitted
     * @see EncodingObjectBuilder#withItemEncoding
     */
    public @Nullable EncodingObject itemEncoding() { return itemEncoding; }
}
