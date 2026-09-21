package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * Allows referencing an external resource for extended documentation.
 */
public class ExternalDocumentationObjectBuilder {
    private @Nullable Map<String, Object> extensions;
    private @Nullable String description;
    private @Nullable URI url;

    /**
     *
     * @param description A short description of the target documentation. <a href="http://spec.commonmark.org/">CommonMark syntax</a>
     *                    MAY be used for rich text representation.
     *
     * @return The current builder
     */
    public ExternalDocumentationObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     *
     * @param url <strong>REQUIRED</strong>. The URL for the target documentation.
     *
     * @return The current builder
     */
    public ExternalDocumentationObjectBuilder withUrl(URI url) {
        this.url = url;
        return this;
    }

    /**
     * @return A new object
     */
    public ExternalDocumentationObject build() {
        return new ExternalDocumentationObject(description, url, extensions);
    }

    /**
     * Creates a builder for an {@link ExternalDocumentationObject}
     *
     * @return A new builder
     */
    public static ExternalDocumentationObjectBuilder externalDocumentationObject() {
        return new ExternalDocumentationObjectBuilder();
    }
    /**
     * @param value the extensions value
     * @return this builder */
    public ExternalDocumentationObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public ExternalDocumentationObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
}
