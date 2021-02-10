package io.muserver.openapi;

import java.util.HashMap;
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

    /**
     * @return A new object
     */
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

    /**
     * Creates a new build by merging two existing response objects
     * @param primary A responses object to use. This is the dominant response who's values will
     *                 be preferred when values cannot be merged (such as {@link ResponseObject#description}
     * @param secondary The other responses object
     * @return A builder that is the merged value of the two given ones
     */
    public static ResponseObjectBuilder mergeResponses(ResponseObject primary, ResponseObject secondary) {


        Map<String, HeaderObject> mergedHeaders = new HashMap<>();
        addHeaders(mergedHeaders, primary);
        addHeaders(mergedHeaders, secondary);

        Map<String, MediaTypeObject> mergedContent = new HashMap<>();
        addContent(mergedContent, primary);
        addContent(mergedContent, secondary);

        Map<String, LinkObject> mergedLinks = new HashMap<>();
        addLinks(mergedLinks, primary);
        addLinks(mergedLinks, secondary);

        return responseObject()
            .withDescription(primary != null ? primary.description : secondary != null ? secondary.description : null)
            .withHeaders(mergedHeaders.isEmpty() ? null : mergedHeaders)
            .withContent(mergedContent.isEmpty() ? null : mergedContent)
            .withLinks(mergedLinks.isEmpty() ? null : mergedLinks);
    }

    private static void addLinks(Map<String, LinkObject> dest, ResponseObject source) {
        if (source != null && source.links != null) {
            for (Map.Entry<String, LinkObject> entry : source.links.entrySet()) {
                String name = entry.getKey();
                if (!dest.containsKey(name)) {
                    dest.put(name, entry.getValue());
                }
            }
        }
    }

    private static void addContent(Map<String, MediaTypeObject> dest, ResponseObject source) {
        if (source != null && source.content != null) {
            for (Map.Entry<String, MediaTypeObject> entry : source.content.entrySet()) {
                String name = entry.getKey();
                if (!dest.containsKey(name)) {
                    dest.put(name, entry.getValue());
                }
            }
        }
    }

    private static void addHeaders(Map<String, HeaderObject> dest, ResponseObject source) {
        if (source != null && source.headers != null) {
            for (Map.Entry<String, HeaderObject> entry : source.headers.entrySet()) {
                String name = entry.getKey();
                if (!dest.containsKey(name)) {
                    dest.put(name, entry.getValue()); // merging headers is too complicated so just add missing headers
                }
            }
        }
    }

}