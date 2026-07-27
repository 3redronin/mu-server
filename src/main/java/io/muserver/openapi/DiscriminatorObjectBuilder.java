package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * <p>When request bodies or response payloads may be one of a number of different schemas, a <code>discriminator</code>
 * object can be used to aid in serialization, deserialization, and validation.  The discriminator is a specific object
 * in a schema which is used to inform the consumer of the specification of an alternative schema based on the value associated with it.</p>
 * <p>When using the discriminator, <em>inline</em> schemas will not be considered.</p>
 */
public class DiscriminatorObjectBuilder {

    /**
     * Creates an empty discriminator builder.
     */
    public DiscriminatorObjectBuilder() {
    }
    private @Nullable String propertyName;
    private @Nullable Map<String, String> mapping;

    /**
     * Sets the discriminator property name.
     *
     * @param propertyName <strong>REQUIRED</strong>. The name of the property in the payload that will hold the discriminator value.
     * @return The current builder
     */
    public DiscriminatorObjectBuilder withPropertyName(String propertyName) {
        this.propertyName = propertyName;
        return this;
    }

    /**
     * Sets the discriminator mapping.
     *
     * @param mapping An object to hold mappings between payload values and schema names or references.
     * @return The current builder
     */
    public DiscriminatorObjectBuilder withMapping(@Nullable Map<String, String> mapping) {
        this.mapping = mapping;
        return this;
    }

    /**
     * Builds the discriminator object.
     *
     * @return A new object
     */
    public DiscriminatorObject build() {
        return new DiscriminatorObject(propertyName, immutable(mapping));
    }

    /**
     * Creates a builder for a {@link DiscriminatorObject}
     *
     * @return A new builder
     */
    public static DiscriminatorObjectBuilder discriminatorObject() {
        return new DiscriminatorObjectBuilder();
    }
}