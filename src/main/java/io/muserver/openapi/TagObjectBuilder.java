package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

/**
 * Adds metadata to a single tag that is used by the {@link OperationObject}. It is not mandatory to have a Tag Object
 * per tag defined in the Operation Object instances.
 */
public class TagObjectBuilder {
    private @Nullable String name;
    private @Nullable String description;
    private @Nullable ExternalDocumentationObject externalDocs;

    /**
     * @param name REQUIRED. The name of the tag.
     * @return The current builder
     */
    public TagObjectBuilder withName(String name) {
        this.name = name;
        return this;
    }

    /**
     * @param description A short description for the tag. CommonMark syntax MAY be used for rich text representation.
     * @return The current builder
     */
    public TagObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     * @param externalDocs Additional external documentation for this tag.
     * @return The current builder
     */
    public TagObjectBuilder withExternalDocs(@Nullable ExternalDocumentationObject externalDocs) {
        this.externalDocs = externalDocs;
        return this;
    }

    /**
     * @return A new object
     */
    public TagObject build() {
        return new TagObject(name, description, externalDocs);
    }

    /**
     * Creates a builder for a {@link TagObjectBuilder}
     * @return A new builder
     */
    public static TagObjectBuilder tagObject() {
        return new TagObjectBuilder();
    }
}