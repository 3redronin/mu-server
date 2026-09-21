package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Adds metadata to a single tag that is used by the {@link OperationObject}. It is not mandatory to have a Tag Object
 * per tag defined in the Operation Object instances.
 */
public class TagObjectBuilder {
    private @Nullable String summary;
    private @Nullable String parent;
    private @Nullable String kind;
    private @Nullable Map<String, Object> extensions;
    private @Nullable String name;
    private @Nullable String description;
    private @Nullable ExternalDocumentationObject externalDocs;

    /**
     *
     * @param name REQUIRED. The name of the tag.
     *
     * @return The current builder
     */
    public TagObjectBuilder withName(String name) {
        this.name = name;
        return this;
    }

    /**
     *
     * @param description A short description for the tag. CommonMark syntax MAY be used for rich text representation.
     *
     * @return The current builder
     */
    public TagObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     *
     * @param externalDocs Additional external documentation for this tag.
     *
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
        return new TagObject(name, description, externalDocs, summary, parent, kind, extensions);
    }

    /**
     * Creates a builder for a {@link TagObjectBuilder}
     * @return A new builder
     */
    public static TagObjectBuilder tagObject() {
        return new TagObjectBuilder();
    }
    /**
     * @param value the extensions value
     * @return this builder */
    public TagObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public TagObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
    /**
     * @param value the OpenAPI 3.2 summary value
     * @return this builder
     */
    public TagObjectBuilder withSummary(@Nullable String value) { this.summary = value; return this; }
    /**
     * @param value the OpenAPI 3.2 parent value
     * @return this builder
     */
    public TagObjectBuilder withParent(@Nullable String value) { this.parent = value; return this; }
    /**
     * @param value the OpenAPI 3.2 kind value
     * @return this builder
     */
    public TagObjectBuilder withKind(@Nullable String value) { this.kind = value; return this; }
}
