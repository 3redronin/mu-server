package io.muserver.openapi;

import java.util.Map;

/**
 * <p>Describes a single response from an API Operation, including design-time, static <code>links</code> to operations
 * based on the response.</p>
 */
public class ResponseObjectBuilder {
    private String description;
    private Map<String, HeaderObject> headers;
    private Map<String, MediaTypeObject> content;
    private Map<String, LinkObject> links;

    /**
     * @param description <strong>REQUIRED</strong>. A short description of the response.
     *                    <a href="http://spec.commonmark.org/">CommonMark syntax</a> MAY be used for rich text representation.
     * @return The current builder
     */
    public ResponseObjectBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * @param headers Maps a header name to its definition. <a href="https://tools.ietf.org/html/rfc7230#page-22">RFC7230</a>
     *                states header names are case insensitive. If a response header is defined with the name
     *                <code>"Content-Type"</code>, it SHALL be ignored.
     * @return The current builder
     */
    public ResponseObjectBuilder withHeaders(Map<String, HeaderObject> headers) {
        this.headers = headers;
        return this;
    }

    /**
     * @param content A map containing descriptions of potential response payloads. The key is a media type or
     *                <a href="https://tools.ietf.org/html/rfc7231#appendix-D">media type range</a> and the value
     *                describes it.  For responses that match multiple keys, only the most specific key is applicable.
     *                e.g. text/plain overrides text/*
     * @return The current builder
     */
    public ResponseObjectBuilder withContent(Map<String, MediaTypeObject> content) {
        this.content = content;
        return this;
    }

    /**
     * @param links A map of operations links that can be followed from the response.
     * @return The current builder
     */
    public ResponseObjectBuilder withLinks(Map<String, LinkObject> links) {
        this.links = links;
        return this;
    }

    public ResponseObject build() {
        return new ResponseObject(description, headers, content, links);
    }

    /**
     * Creates a builder for a {@link ResponseObject}
     *
     * @return A new builder
     */
    public static ResponseObjectBuilder responseObject() {
        return new ResponseObjectBuilder();
    }
}