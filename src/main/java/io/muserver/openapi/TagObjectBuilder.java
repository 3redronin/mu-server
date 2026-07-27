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
     * Creates an empty tag object builder.
     */
    public TagObjectBuilder() {
    }

    /**
     * Sets the tag name.
     *
     * @param name REQUIRED. The name of the tag.
     * @return The current builder
     */
    public TagObjectBuilder withName(String name) {
        this.name = name;
        return this;
    }

    /**
     * Sets the tag description.
     *
     * @param description A short description for the tag. CommonMark syntax MAY be used for rich text representation.
     * @return The current builder
     */
    public TagObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     * Sets the tag external documentation.
     *
     * @param externalDocs Additional external documentation for this tag.
     * @return The current builder
     */
    public TagObjectBuilder withExternalDocs(@Nullable ExternalDocumentationObject externalDocs) {
        this.externalDocs = externalDocs;
        return this;
    }

    /**
     * Builds a tag object from the configured values.
     *
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