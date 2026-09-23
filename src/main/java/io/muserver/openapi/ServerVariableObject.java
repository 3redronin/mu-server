package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * Describes a variable used in a server URL.
 *
 * @see ServerVariableObjectBuilder
 */
public class ServerVariableObject implements JsonWriter {
    private final Map<String, Object> extensions;
    private final @Nullable List<String> enumValues;
    private final String defaultValue;
    private final @Nullable String description;

    ServerVariableObject(@Nullable List<String> enumValues, @Nullable String defaultValue, @Nullable String description, @Nullable Map<String, Object> extensions) {
        this.extensions = Extensions.copy(extensions);
        if (enumValues != null && (enumValues.isEmpty() || !enumValues.contains(defaultValue))) {
            throw new IllegalArgumentException("Server variable enum must be nonempty and contain the default");
        }
        this.enumValues = enumValues;
        notNull("defaultValue", defaultValue);
        this.defaultValue = java.util.Objects.requireNonNull(defaultValue);
        this.description = description;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write("{");
        boolean isFirst = true;
        isFirst = append(writer, "enum", enumValues, isFirst);
        isFirst = append(writer, "default", defaultValue, isFirst);
        isFirst = append(writer, "description", description, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write("}");
    }

    /**
     * Gets the allowed values for the server variable.
     *
     * @return the value described by {@link ServerVariableObjectBuilder#withEnumValues}
     */
    public @Nullable List<String> enumValues() {
        return enumValues;
    }

    /**
     * Gets the default value for the server variable.
     *
     * @return the value described by {@link ServerVariableObjectBuilder#withDefaultValue}
     */
    public String defaultValue() {
        return defaultValue;
    }

    /**
     * Gets the server variable description.
     *
     * @return the value described by {@link ServerVariableObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public ServerVariableObjectBuilder toBuilder() {
        return new ServerVariableObjectBuilder()
            .withExtensions(extensions).withEnumValues(enumValues).withDefaultValue(defaultValue).withDescription(description);
    }
}
