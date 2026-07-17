package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * A builder for {@link ExampleObject} objects
 */
public class ExampleObjectBuilder {
    private @Nullable String summary;
    private @Nullable String description;
    private @Nullable Object value;
    private @Nullable URI externalValue;

    /**
     * @param summary Short description for the example.
     * @return The current builder
     */
    public ExampleObjectBuilder withSummary(@Nullable String summary) {
        this.summary = summary;
        return this;
    }

    /**
     * @param description Long description for the example. <a href="http://spec.commonmark.org/">CommonMark syntax</a>
     *                    MAY be used for rich text representation.
     * @return The current builder
     */
    public ExampleObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     * @param value Embedded literal example. The <code>value</code> field and <code>externalValue</code> field are mutually
     *              exclusive. To represent examples of media types that cannot naturally represented in JSON or YAML, use
     *              a string value to contain the example, escaping where necessary.
     * @return The current builder
     */
    public ExampleObjectBuilder withValue(@Nullable Object value) {
        this.value = value;
        return this;
    }

    /**
     * @param externalValue A URL that points to the literal example. This provides the capability to reference examples that
     *                      cannot easily be included in JSON or YAML documents.  The <code>value</code> field
     *                      and <code>externalValue</code> field are mutually exclusive.
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
        return new ExampleObject(summary, description, value, externalValue);
    }

    /**
     * Creates a builder for an {@link ExampleObject}
     *
     * @return A new builder
     */
    public static ExampleObjectBuilder exampleObject() {
        return new ExampleObjectBuilder();
    }
}