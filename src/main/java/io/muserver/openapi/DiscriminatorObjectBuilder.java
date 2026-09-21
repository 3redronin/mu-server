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
    private @Nullable String defaultMapping;
    private @Nullable Map<String, Object> extensions;
    private @Nullable String propertyName;
    private @Nullable Map<String, String> mapping;

    /**
     *
     * @param propertyName <strong>REQUIRED</strong>. The name of the property in the payload that will hold the discriminator value.
     *
     * @return The current builder
     */
    public DiscriminatorObjectBuilder withPropertyName(String propertyName) {
        this.propertyName = propertyName;
        return this;
    }

    /**
     *
     * @param mapping An object to hold mappings between payload values and schema names or references.
     *
     * @return The current builder
     */
    public DiscriminatorObjectBuilder withMapping(@Nullable Map<String, String> mapping) {
        this.mapping = mapping;
        return this;
    }

    /**
     * @return A new object
     */
    public DiscriminatorObject build() {
        return new DiscriminatorObject(propertyName, immutable(mapping), defaultMapping, extensions);
    }

    /**
     * Creates a builder for a {@link DiscriminatorObject}
     *
     * @return A new builder
     */
    public static DiscriminatorObjectBuilder discriminatorObject() {
        return new DiscriminatorObjectBuilder();
    }
    /**
     * @param value the extensions value
     * @return this builder */
    public DiscriminatorObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public DiscriminatorObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
    /**
     * @param value the OpenAPI 3.2 defaultMapping value
     * @return this builder
     */
    public DiscriminatorObjectBuilder withDefaultMapping(@Nullable String value) { this.defaultMapping = value; return this; }
}
