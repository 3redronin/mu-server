package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import java.util.List;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * An object representing a Server Variable for server URL template substitution.
 */
public class ServerVariableObjectBuilder {
    private @Nullable Map<String, Object> extensions;
    private @Nullable List<String> enumValues;
    private @Nullable String defaultValue;
    private @Nullable String description;

    /**
     *
     * @param enumValues An enumeration of string values to be used if the substitution options are from a limited set.
     *
     * @return The current builder
     */
    public ServerVariableObjectBuilder withEnumValues(@Nullable List<String> enumValues) {
        this.enumValues = enumValues;
        return this;
    }

    /**
     *
     * @param defaultValue <b>REQUIRED.</b> The default value to use for substitution, and to send, if an alternate value is not supplied. Unlike the
     * Schema Object's default, this value MUST be provided by the consumer.
     *
     * @return The current builder
     */
    public ServerVariableObjectBuilder withDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    /**
     *
     * @param description An optional description for the server variable. CommonMark syntax MAY be used for rich text representation.
     *
     * @return The current builder
     */
    public ServerVariableObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     * @return A new object
     */
    public ServerVariableObject build() {
        return new ServerVariableObject(immutable(enumValues), defaultValue, description, extensions);
    }

    /**
     * Creates a builder for a {@link ServerVariableObjectBuilder}
     * @return A new builder
     */
    public static ServerVariableObjectBuilder serverVariableObject() {
        return new ServerVariableObjectBuilder();
    }
    /**
     * @param value the extensions value
     * @return this builder */
    public ServerVariableObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public ServerVariableObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
}
