package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Objects;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see TagObjectBuilder
 */
public class TagObject implements JsonWriter {
    private final @Nullable String summary;
    private final @Nullable String parent;
    private final @Nullable String kind;
    private final Map<String, Object> extensions;

    private final String name;
    private final @Nullable String description;
    private final @Nullable ExternalDocumentationObject externalDocs;

    TagObject(@Nullable String name, @Nullable String description, @Nullable ExternalDocumentationObject externalDocs, @Nullable String summary, @Nullable String parent, @Nullable String kind, @Nullable Map<String, Object> extensions) {
        this.summary = summary;
        this.parent = parent;
        this.kind = kind;
        this.extensions = Extensions.copy(extensions);
        notNull("name", name);
        this.name = java.util.Objects.requireNonNull(name);
        this.description = description;
        this.externalDocs = externalDocs;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "name", name, isFirst);
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "externalDocs", externalDocs, isFirst);
        isFirst = Jsonizer.append(writer, "summary", summary, isFirst);
        isFirst = Jsonizer.append(writer, "parent", parent, isFirst);
        isFirst = Jsonizer.append(writer, "kind", kind, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write('}');
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TagObject tagObject = (TagObject) o;
        return name.equals(tagObject.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    /**
     * @return the value described by {@link TagObjectBuilder#withName}
     */
    public String name() {
        return name;
    }

    /**
      @return the value described by {@link TagObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
      @return the value described by {@link TagObjectBuilder#withExternalDocs}
     */
    public @Nullable ExternalDocumentationObject externalDocs() {
        return externalDocs;
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public TagObjectBuilder toBuilder() {
        return new TagObjectBuilder()
            .withSummary(summary).withParent(parent).withKind(kind).withExtensions(extensions).withName(name).withDescription(description).withExternalDocs(externalDocs);
    }
    /** @return the OpenAPI 3.2 summary value */
    public @Nullable String summary() { return summary; }
    /** @return the OpenAPI 3.2 parent value */
    public @Nullable String parent() { return parent; }
    /** @return the OpenAPI 3.2 kind value */
    public @Nullable String kind() { return kind; }
}
