package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Objects;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see TagObjectBuilder
 */
public class TagObject implements JsonWriter {

    /**
     * @deprecated use {@link #name()} instead
     */
    @Deprecated
    public final String name;
    /**
      @deprecated use {@link #description()} instead
     */
    @Deprecated
    public final String description;
    /**
      @deprecated use {@link #externalDocs()} instead
     */
    @Deprecated
    public final ExternalDocumentationObject externalDocs;

    TagObject(String name, String description, ExternalDocumentationObject externalDocs) {
        notNull("name", name);
        this.name = name;
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
    public boolean equals(Object o) {
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
    public String description() {
        return description;
    }

    /**
      @return the value described by {@link TagObjectBuilder#withExternalDocs}
     */
    public ExternalDocumentationObject externalDocs() {
        return externalDocs;
    }
}
