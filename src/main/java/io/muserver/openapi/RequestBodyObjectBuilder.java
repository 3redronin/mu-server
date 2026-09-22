package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * Describes a single request body.
 */
public class RequestBodyObjectBuilder {
    private @Nullable Map<String, Object> extensions;
    private @Nullable String description;
    private @Nullable Map<String, ReferenceOr<MediaTypeObject>> content;
    private @Nullable Boolean required;

    /**
     *
     * @param description A brief description of the request body. This could contain examples of use.
     *                    <a href="http://spec.commonmark.org/">CommonMark syntax</a> MAY be used for rich text representation.
     *
     * @return The current builder
     */
    public RequestBodyObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     *
     * @param content <strong>REQUIRED</strong>. The content of the request body. The key is a media type or
     *                <a href="https://tools.ietf.org/html/rfc7231#appendix-D">media type range</a> and the value describes it.
     *                For requests that match multiple keys, only the most specific key is applicable. e.g. text/plain overrides text/*
     *
     * @return The current builder
     */
    public RequestBodyObjectBuilder withContent(Map<String, MediaTypeObject> content) {
        this.content = ReferenceValues.inline(content);
        return this;
    }

    /**
     *
     * @param required Determines if the request body is required in the request. Defaults to <code>false</code>.
     *
     * @return The current builder
     */
    public RequestBodyObjectBuilder withRequired(@Nullable Boolean required) {
        this.required = required;
        return this;
    }

    /**
     * @return A new object
     */
    public RequestBodyObject build() {
        return new RequestBodyObject(description, immutable(content), required, extensions);
    }

    /**
     * Creates a builder for a {@link RequestBodyObject}
     *
     * @return A new builder
     */
    public static RequestBodyObjectBuilder requestBodyObject() {
        return new RequestBodyObjectBuilder();
    }
    /**
     * @param value the extensions value
     * @return this builder */
    public RequestBodyObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public RequestBodyObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
    /** @param value inline media types and references
     * @return this builder */
    public RequestBodyObjectBuilder withContentOrReferences(@Nullable Map<String, ReferenceOr<MediaTypeObject>> value) { this.content = value; return this; }
}
