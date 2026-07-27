package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * Allows referencing an external resource for extended documentation.
 */
public class ExternalDocumentationObjectBuilder {
    private @Nullable String description;
    private @Nullable URI url;

    /**
     * Creates an empty external documentation builder.
     */
    public ExternalDocumentationObjectBuilder() {
    }

    /**
     * Sets the documentation description.
     *
     * @param description A short description of the target documentation. <a href="http://spec.commonmark.org/">CommonMark syntax</a>
     *                    MAY be used for rich text representation.
     * @return The current builder
     */
    public ExternalDocumentationObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     * Sets the target documentation URL.
     *
     * @param url <strong>REQUIRED</strong>. The URL for the target documentation.
     * @return The current builder
     */
    public ExternalDocumentationObjectBuilder withUrl(URI url) {
        this.url = url;
        return this;
    }

    /**
     * Builds the external documentation object.
     *
     * @return A new object
     */
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