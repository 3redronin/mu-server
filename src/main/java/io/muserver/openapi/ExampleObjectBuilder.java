package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * A builder for {@link ExampleObject} objects
 */
public class ExampleObjectBuilder {
    private @Nullable Object dataValue;
    private @Nullable String serializedValue;
    private @Nullable Map<String, Object> extensions;
    private @Nullable String summary;
    private @Nullable String description;
    private @Nullable Object value;
    private @Nullable URI externalValue;

    /**
     *
     * @param summary Short description for the example.
     *
     * @return The current builder
     */
    public ExampleObjectBuilder withSummary(@Nullable String summary) {
        this.summary = summary;
        return this;
    }

    /**
     *
     * @param description Long description for the example. <a href="http://spec.commonmark.org/">CommonMark syntax</a>
     *                    MAY be used for rich text representation.
     *
     * @return The current builder
     */
    public ExampleObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     *
     * @param value Embedded literal example. The <code>value</code> field and <code>externalValue</code> field are mutually
     *              exclusive. To represent examples of media types that cannot naturally represented in JSON or YAML, use
     *              a string value to contain the example, escaping where necessary.
     *
     * @return The current builder
     */
    public ExampleObjectBuilder withValue(@Nullable Object value) {
        this.value = value;
        return this;
    }

    /**
     *
     * @param externalValue A URL that points to the literal example. This provides the capability to reference examples that
     *                      cannot easily be included in JSON or YAML documents.  The <code>value</code> field
     *                      and <code>externalValue</code> field are mutually exclusive.
     *
     * @return The current builder
     */
    public ExampleObjectBuilder withExternalValue(@Nullable URI externalValue) {
        this.externalValue = externalValue;
        return this;
    }

    /**
     * @return A new object
     */
    public ExampleObject build() {
        return new ExampleObject(summary, description, value, externalValue, dataValue, serializedValue, extensions);
    }

    /**
     * Creates a builder for an {@link ExampleObject}
     *
     * @return A new builder
     */
    public static ExampleObjectBuilder exampleObject() {
        return new ExampleObjectBuilder();
    }
    /**
     * @param value the extensions value
     * @return this builder */
    public ExampleObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public ExampleObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
    /**
     * @param value the OpenAPI 3.2 dataValue value
     * @return this builder
     */
    public ExampleObjectBuilder withDataValue(@Nullable Object value) { this.dataValue = value; return this; }
    /**
     * @param value the OpenAPI 3.2 serializedValue value
     * @return this builder
     */
    public ExampleObjectBuilder withSerializedValue(@Nullable String value) { this.serializedValue = value; return this; }
}
