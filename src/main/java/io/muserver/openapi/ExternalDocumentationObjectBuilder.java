package io.muserver.openapi;

import java.net.URI;

/**
 * Allows referencing an external resource for extended documentation.
 */
public class ExternalDocumentationObjectBuilder {
    private String description;
    private URI url;

    /**
     * @param description A short description of the target documentation. <a href="http://spec.commonmark.org/">CommonMark syntax</a>
     *                    MAY be used for rich text representation.
     * @return The current builder
     */
    public ExternalDocumentationObjectBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * @param url <strong>REQUIRED</strong>. The URL for the target documentation.
     * @return The current builder
     */
    public ExternalDocumentationObjectBuilder withUrl(URI url) {
        this.url = url;
        return this;
    }

    public ExternalDocumentationObject build() {
        return new ExternalDocumentationObject(description, url);
    }

    /**
     * Creates a builder for an {@link ExternalDocumentationObject}
     *
     * @return A new builder
     */
    public static ExternalDocumentationObjectBuilder externalDocumentationObject() {
        return new ExternalDocumentationObjectBuilder();
    }
}