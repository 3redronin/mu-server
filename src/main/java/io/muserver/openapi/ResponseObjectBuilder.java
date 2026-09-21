package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * <p>Describes a single response from an API Operation, including design-time, static <code>links</code> to operations
 * based on the response.</p>
 */
public class ResponseObjectBuilder {
    private @Nullable Map<String, Object> extensions;
    private @Nullable String description;
    private @Nullable Map<String, ReferenceOr<HeaderObject>> headers;
    private @Nullable Map<String, MediaTypeObject> content;
    private @Nullable Map<String, ReferenceOr<LinkObject>> links;

    /**
     *
     * @param description <strong>REQUIRED</strong>. A short description of the response.
     *                    <a href="http://spec.commonmark.org/">CommonMark syntax</a> MAY be used for rich text representation.
     *
     * @return The current builder
     */
    public ResponseObjectBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     *
     * @param headers Maps a header name to its definition. <a href="https://tools.ietf.org/html/rfc7230#page-22">RFC7230</a>
     *                states header names are case insensitive. If a response header is defined with the name
     *                <code>"Content-Type"</code>, it SHALL be ignored.
     *
     * @return The current builder
     */
    public ResponseObjectBuilder withHeaders(@Nullable Map<String, HeaderObject> headers) {
        this.headers = ReferenceValues.inline(headers);
        return this;
    }

    /**
     *
     * @param content A map containing descriptions of potential response payloads. The key is a media type or
     *                <a href="https://tools.ietf.org/html/rfc7231#appendix-D">media type range</a> and the value
     *                describes it.  For responses that match multiple keys, only the most specific key is applicable.
     *                e.g. text/plain overrides text/*
     *
     * @return The current builder
     */
    public ResponseObjectBuilder withContent(@Nullable Map<String, MediaTypeObject> content) {
        this.content = content;
        return this;
    }

    /**
     *
     * @param links A map of operations links that can be followed from the response.
     *
     * @return The current builder
     */
    public ResponseObjectBuilder withLinks(@Nullable Map<String, LinkObject> links) {
        this.links = ReferenceValues.inline(links);
        return this;
    }

    /**
     * @return A new object
     */
    public ResponseObject build() {
        return new ResponseObject(description, immutable(headers), immutable(content), immutable(links), extensions);
    }

    /**
     * Creates a builder for a {@link ResponseObject}
     *
     * @return A new builder
     */
    public static ResponseObjectBuilder responseObject() {
        return new ResponseObjectBuilder();
    }

    /**
     * Creates a new build by merging two existing response objects
     *
     * @param primary A responses object to use. This is the dominant response who's values will
     *                 be preferred when values cannot be merged (such as {@link ResponseObject#description}
     *
     * @param secondary The other responses object
     *
     * @return A builder that is the merged value of the two given ones
     */
    public static ResponseObjectBuilder mergeResponses(@Nullable ResponseObject primary, @Nullable ResponseObject secondary) {


        if (primary == null) return secondary == null ? responseObject() : secondary.toBuilder();
        if (secondary == null) return primary.toBuilder();
        Map<String, ReferenceOr<HeaderObject>> headers = new java.util.TreeMap<>();
        if (secondary.headersOrReferences() != null) headers.putAll(secondary.headersOrReferences());
        if (primary.headersOrReferences() != null) headers.putAll(primary.headersOrReferences());
        Map<String, ReferenceOr<LinkObject>> links = new java.util.TreeMap<>();
        if (secondary.linksOrReferences() != null) links.putAll(secondary.linksOrReferences());
        if (primary.linksOrReferences() != null) links.putAll(primary.linksOrReferences());
        Map<String, MediaTypeObject> content = mergeContent(primary.content(), secondary.content());
        return primary.toBuilder().withHeadersOrReferences(headers.isEmpty() ? null : headers)
            .withLinksOrReferences(links.isEmpty() ? null : links).withContent(content.isEmpty() ? null : content);
    }

    /** Combines all media types and payload alternatives.
     *
     * @param primary first content map
     *
     * @param secondary second content map
     *
     * @return the merged content */
    public static Map<String, MediaTypeObject> mergeContent(@Nullable Map<String, MediaTypeObject> primary, @Nullable Map<String, MediaTypeObject> secondary) {
        Map<String, MediaTypeObject> merged = new java.util.TreeMap<>();
        if (primary != null) merged.putAll(primary);
        if (secondary != null) secondary.forEach((key, value) -> merged.merge(key, value,
            (a, b) -> MediaTypeObjectBuilder.mergeMediaTypes(a, b).build()));
        return merged;
    }

    /**
     * @param value the extensions value
     * @return this builder */
    public ResponseObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public ResponseObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
    /**
     * @param value inline values and references for headers
     * @return this builder */
    public ResponseObjectBuilder withHeadersOrReferences(@Nullable Map<String, ReferenceOr<HeaderObject>> value) { this.headers = value; return this; }
    /**
     * @param value inline values and references for links
     * @return this builder */
    public ResponseObjectBuilder withLinksOrReferences(@Nullable Map<String, ReferenceOr<LinkObject>> value) { this.links = value; return this; }
}
