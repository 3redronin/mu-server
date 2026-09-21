package io.muserver.openapi;

import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.io.Writer;
import java.util.Objects;

/** An inline OpenAPI object or a Reference Object pointing to one.
 * @param <T> the inline object type */
public final class ReferenceOr<T> implements JsonWriter {
    private final @Nullable T value;
    private final @Nullable ReferenceObject reference;
    private ReferenceOr(@Nullable T value, @Nullable ReferenceObject reference) { this.value = value; this.reference = reference; }
    /**
     * @param value an inline value
     * @param <T> its type
     * @return the inline wrapper */
    public static <T> ReferenceOr<T> inline(T value) { return new ReferenceOr<>(Objects.requireNonNull(value), null); }
    /**
     * @param reference a reference
     * @param <T> its target type
     * @return the reference wrapper */
    public static <T> ReferenceOr<T> reference(ReferenceObject reference) { return new ReferenceOr<>(null, Objects.requireNonNull(reference)); }
    /**
     * @param ref a URI reference
     * @param <T> its target type
     * @return the reference wrapper */
    public static <T> ReferenceOr<T> reference(String ref) { return reference(ReferenceObjectBuilder.referenceObject().withRef(ref).build()); }
    /** @return whether this value is a reference */
    public boolean isReference() { return reference != null; }
    /** @return the reference, or null for an inline value */
    public @Nullable ReferenceObject reference() { return reference; }
    /**
     * @return the inline value
     * @throws IllegalStateException if this is a reference */
    public T value() {
        if (reference != null) throw new IllegalStateException("This value is a reference; use the reference-capable accessor");
        return Objects.requireNonNull(value);
    }
    @Override public void writeJson(Writer writer) throws IOException { Jsonizer.writeValue(writer, reference == null ? value : reference); }
}
