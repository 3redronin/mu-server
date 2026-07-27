package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.List;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * An object representing a Server Variable for server URL template substitution.
 */
public class ServerVariableObjectBuilder {
    private @Nullable List<String> enumValues;
    private @Nullable String defaultValue;
    private @Nullable String description;

    /**
     * Creates an empty server variable object builder.
     */
    public ServerVariableObjectBuilder() {
    }

    /**
     * Sets the allowed values for the server variable.
     *
     * @param enumValues An enumeration of string values to be used if the substitution options are from a limited set.
     * @return The current builder
     */
    public ServerVariableObjectBuilder withEnumValues(@Nullable List<String> enumValues) {
        this.enumValues = enumValues;
        return this;
    }

    /**
     * Sets the default value for the server variable.
     *
     * @param defaultValue <b>REQUIRED.</b> The default value to use for substitution, and to send, if an alternate value is not supplied. Unlike the
     * Schema Object's default, this value MUST be provided by the consumer.
     * @return The current builder
     */
    public ServerVariableObjectBuilder withDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    /**
     * Sets the server variable description.
     *
     * @param description An optional description for the server variable. CommonMark syntax MAY be used for rich text representation.
     * @return The current builder
     */
    public ServerVariableObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     * Builds a server variable object from the configured values.
     *
     * @return A new object
     */
    public ServerVariableObject build() {
        return new ServerVariableObject(immutable(enumValues), defaultValue, description);
    }

    /**
     * Creates a builder for a {@link ServerVariableObjectBuilder}
     * @return A new builder
     */
    public static ServerVariableObjectBuilder serverVariableObject() {
        return new ServerVariableObjectBuilder();
    }
}