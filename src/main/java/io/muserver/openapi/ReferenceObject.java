package io.muserver.openapi;

import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.io.Writer;
import java.util.Objects;

/** An OpenAPI Reference Object. Schema and Path Item $ref keywords use their respective objects. */
public final class ReferenceObject implements JsonWriter {
    private final String ref;
    private final @Nullable String summary;
    private final @Nullable String description;
    ReferenceObject(String ref, @Nullable String summary, @Nullable String description) {
        this.ref = Objects.requireNonNull(ref, "$ref");
        java.net.URI.create(ref);
        this.summary = summary;
        this.description = description;
    }
    /** @return the URI reference */
    public String ref() { return ref; }
    /** @return the summary override */
    public @Nullable String summary() { return summary; }
    /** @return the description override */
    public @Nullable String description() { return description; }
    /** @return a builder preserving all fields */
    public ReferenceObjectBuilder toBuilder() { return new ReferenceObjectBuilder().withRef(ref).withSummary(summary).withDescription(description); }
    @Override public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean first = Jsonizer.append(writer, "$ref", ref, true);
        first = Jsonizer.append(writer, "summary", summary, first);
        Jsonizer.append(writer, "description", description, first);
        writer.write('}');
    }
}
