package io.muserver.openapi;

import java.util.List;

/**
 * An object representing a Server Variable for server URL template substitution.
 */
public class ServerVariableObjectBuilder {
    private List<String> enumValues;
    private String defaultValue;
    private String description;

    /**
     * @param enumValues An enumeration of string values to be used if the substitution options are from a limited set.
     * @return The current builder
     */
    public ServerVariableObjectBuilder withEnumValues(List<String> enumValues) {
        this.enumValues = enumValues;
        return this;
    }

    /**
     * @param defaultValue <b>REQUIRED.</b> The default value to use for substitution, and to send, if an alternate value is not supplied. Unlike the
     * Schema Object's default, this value MUST be provided by the consumer.
     * @return The current builder
     */
    public ServerVariableObjectBuilder withDefaultValue(String defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    /**
     * @param description An optional description for the server variable. CommonMark syntax MAY be used for rich text representation.
     * @return The current builder
     */
    public ServerVariableObjectBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public ServerVariableObject build() {
        return new ServerVariableObject(enumValues, defaultValue, description);
    }

    /**
     * Creates a builder for a {@link ServerVariableObjectBuilder}
     * @return A new builder
     */
    public static ServerVariableObjectBuilder serverVariableObject() {
        return new ServerVariableObjectBuilder();
    }
}