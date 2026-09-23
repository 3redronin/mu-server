package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * Maps response status codes to OpenAPI response descriptions.
 *
 * @see ResponsesObjectBuilder
 */
public class ResponsesObject implements JsonWriter {
    private final Map<String, Object> extensions;

    private final @Nullable ReferenceOr<ResponseObject> defaultValue;
    private final Map<String, ReferenceOr<ResponseObject>> httpStatusCodes;

    ResponsesObject(@Nullable ReferenceOr<ResponseObject> defaultValue, @Nullable Map<String, ReferenceOr<ResponseObject>> httpStatusCodes, @Nullable Map<String, Object> extensions) {
        this.extensions = Extensions.copy(extensions);
        if (httpStatusCodes == null) httpStatusCodes = java.util.Collections.emptyMap();
        for (String code : httpStatusCodes.keySet()) {
            if (!code.matches("[1-5](?:[0-9]{2}|XX)")) throw new IllegalArgumentException("Invalid response key: " + code);
        }
        if (httpStatusCodes.isEmpty() && defaultValue == null) {
            throw new IllegalArgumentException("'httpStatusCodes' must contain at least one value");
        }
        this.defaultValue = defaultValue;
        this.httpStatusCodes = httpStatusCodes;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "default", defaultValue, isFirst);
        for (Map.Entry<String, ReferenceOr<ResponseObject>> entry : httpStatusCodes.entrySet()) {
            isFirst = append(writer, entry.getKey(), entry.getValue(), isFirst);
        }
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write('}');
    }

    /**
     * Gets the default response definition.
     *
     * @return the value described by {@link ResponsesObjectBuilder#withDefaultValue}
     */
    public @Nullable ResponseObject defaultValue() {
        return ReferenceValues.values(defaultValue);
    }

    /**
     * Gets the response definitions keyed by status code.
     *
     * @return the value described by {@link ResponsesObjectBuilder#withHttpStatusCodes}
     */
    public Map<String, ResponseObject> httpStatusCodes() {
        return java.util.Objects.requireNonNull(ReferenceValues.values(httpStatusCodes));
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return inline values and references for defaultValue */
    public @Nullable ReferenceOr<ResponseObject> defaultValueOrReferences() { return defaultValue; }
    /** @return inline values and references for httpStatusCodes */
    public Map<String, ReferenceOr<ResponseObject>> httpStatusCodesOrReferences() { return httpStatusCodes; }
    /** @return a builder preserving all fields and extensions */
    public ResponsesObjectBuilder toBuilder() {
        return new ResponsesObjectBuilder()
            .withExtensions(extensions).withDefaultValueOrReferences(defaultValue).withHttpStatusCodesOrReferences(httpStatusCodes);
    }
}
