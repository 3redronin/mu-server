package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see DiscriminatorObjectBuilder
 */
public class DiscriminatorObject implements JsonWriter {
    private final @Nullable String defaultMapping;
    private final Map<String, Object> extensions;
    private final String propertyName;
    private final @Nullable Map<String, String> mapping;

    DiscriminatorObject(@Nullable String propertyName, @Nullable Map<String, String> mapping, @Nullable String defaultMapping, @Nullable Map<String, Object> extensions) {
        this.defaultMapping = defaultMapping;
        this.extensions = Extensions.copy(extensions);
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
        isFirst = Jsonizer.append(writer, "defaultMapping", defaultMapping, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write('}');
    }

    /**
     * @return The value described by {@link DiscriminatorObjectBuilder#withPropertyName}
     */
    public String propertyName() {
        return propertyName;
    }

    /**
      @return The value described by {@link DiscriminatorObjectBuilder#withMapping}
     */
    public @Nullable Map<String, String> mapping() {
        return mapping;
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public DiscriminatorObjectBuilder toBuilder() {
        return new DiscriminatorObjectBuilder()
            .withDefaultMapping(defaultMapping).withExtensions(extensions).withPropertyName(propertyName).withMapping(mapping);
    }
    /**
     * @return the fallback schema name or URI reference, or null when omitted
     * @see DiscriminatorObjectBuilder#withDefaultMapping
     */
    public @Nullable String defaultMapping() { return defaultMapping; }
}
