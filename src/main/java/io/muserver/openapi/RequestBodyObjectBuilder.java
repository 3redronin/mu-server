package io.muserver.openapi;

import java.util.Map;

/**
 * Describes a single request body.
 */
public class RequestBodyObjectBuilder {
    private String description;
    private Map<String, MediaTypeObject> content;
    private boolean required;

    /**
     * @param description A brief description of the request body. This could contain examples of use.
     *                    <a href="http://spec.commonmark.org/">CommonMark syntax</a> MAY be used for rich text representation.
     * @return The current builder
     */
    public RequestBodyObjectBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * @param content <strong>REQUIRED</strong>. The content of the request body. The key is a media type or
     *                <a href="https://tools.ietf.org/html/rfc7231#appendix-D">media type range</a> and the value describes it.
     *                For requests that match multiple keys, only the most specific key is applicable. e.g. text/plain overrides text/*
     * @return The current builder
     */
    public RequestBodyObjectBuilder withContent(Map<String, MediaTypeObject> content) {
        this.content = content;
        return this;
    }

    /**
     * @param required Determines if the request body is required in the request. Defaults to <code>false</code>.
     * @return The current builder
     */
    public RequestBodyObjectBuilder withRequired(boolean required) {
        this.required = required;
        return this;
    }

    public RequestBodyObject build() {
        return new RequestBodyObject(description, content, required);
    }

    /**
     * Creates a builder for a {@link RequestBodyObject}
     *
     * @return A new builder
     */
    public static RequestBodyObjectBuilder requestBodyObject() {
        return new RequestBodyObjectBuilder();
    }
}