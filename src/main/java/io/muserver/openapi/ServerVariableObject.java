package io.muserver.openapi;

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
    private final @Nullable List<String> enumValues;
    private final String defaultValue;
    private final @Nullable String description;

    ServerVariableObject(@Nullable List<String> enumValues, @Nullable String defaultValue, @Nullable String description) {
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
}
