package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * Defines a discriminator used to select among polymorphic schemas.
 *
 * @see DiscriminatorObjectBuilder
 */
public class DiscriminatorObject implements JsonWriter {
    private final String propertyName;
    private final @Nullable Map<String, String> mapping;

    DiscriminatorObject(@Nullable String propertyName, @Nullable Map<String, String> mapping) {
        notNull("propertyName", propertyName);
        this.propertyName = java.util.Objects.requireNonNull(propertyName);
        this.mapping = mapping;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "propertyName", propertyName, isFirst);
        isFirst = append(writer, "mapping", mapping, isFirst);
        writer.write('}');
    }

    /**
     * Returns the discriminator property name.
     *
     * @return The value described by {@link DiscriminatorObjectBuilder#withPropertyName}
     */
    public String propertyName() {
        return propertyName;
    }

    /**
     * Returns the discriminator mapping.
     *
      @return The value described by {@link DiscriminatorObjectBuilder#withMapping}
     */
    public @Nullable Map<String, String> mapping() {
        return mapping;
    }
}
