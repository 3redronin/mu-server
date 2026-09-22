package io.muserver.openapi;

import org.jspecify.annotations.Nullable;
import java.util.Objects;

/** Builds an OpenAPI Reference Object. */
public final class ReferenceObjectBuilder {
    private @Nullable String ref;
    private @Nullable String summary;
    private @Nullable String description;
    /** @return a new builder */
    public static ReferenceObjectBuilder referenceObject() { return new ReferenceObjectBuilder(); }
    /**
     * @param value the required URI reference
     * @return this builder */
    public ReferenceObjectBuilder withRef(String value) { ref = value; return this; }
    /**
     * @param value the summary override
     * @return this builder */
    public ReferenceObjectBuilder withSummary(@Nullable String value) { summary = value; return this; }
    /**
     * @param value the description override
     * @return this builder */
    public ReferenceObjectBuilder withDescription(@Nullable String value) { description = value; return this; }
    /** @return the immutable reference */
    public ReferenceObject build() { return new ReferenceObject(Objects.requireNonNull(ref, "$ref"), summary, description); }
}
