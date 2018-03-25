package io.muserver.openapi;

import java.net.URI;

public class ExampleObjectBuilder {
    private String summary;
    private String description;
    private Object value;
    private URI externalValue;

    /**
     * @param summary Short description for the example.
     * @return The current builder
     */
    public ExampleObjectBuilder withSummary(String summary) {
        this.summary = summary;
        return this;
    }

    /**
     * @param description Long description for the example. <a href="http://spec.commonmark.org/">CommonMark syntax</a>
     *                    MAY be used for rich text representation.
     * @return The current builder
     */
    public ExampleObjectBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * @param value Embedded literal example. The <code>value</code> field and <code>externalValue</code> field are mutually
     *              exclusive. To represent examples of media types that cannot naturally represented in JSON or YAML, use
     *              a string value to contain the example, escaping where necessary.
     * @return The current builder
     */
    public ExampleObjectBuilder withValue(Object value) {
        this.value = value;
        return this;
    }

    /**
     * @param externalValue A URL that points to the literal example. This provides the capability to reference examples that
     *                      cannot easily be included in JSON or YAML documents.  The <code>value</code> field
     *                      and <code>externalValue</code> field are mutually exclusive.
     * @return The current builder
     */
    public ExampleObjectBuilder withExternalValue(URI externalValue) {
        this.externalValue = externalValue;
        return this;
    }

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