package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Objects;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * Adds descriptive metadata for a group of API operations.
 *
 * @see TagObjectBuilder
 */
public class TagObject implements JsonWriter {

    private final String name;
    private final @Nullable String description;
    private final @Nullable ExternalDocumentationObject externalDocs;

    TagObject(@Nullable String name, @Nullable String description, @Nullable ExternalDocumentationObject externalDocs) {
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
     * Gets the tag name.
     *
     * @return the value described by {@link TagObjectBuilder#withName}
     */
    public String name() {
        return name;
    }

    /**
     * Gets the tag description.
     *
     * @return the value described by {@link TagObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
     * Gets the tag external documentation.
     *
     * @return the value described by {@link TagObjectBuilder#withExternalDocs}
     */
    public @Nullable ExternalDocumentationObject externalDocs() {
        return externalDocs;
    }
}
