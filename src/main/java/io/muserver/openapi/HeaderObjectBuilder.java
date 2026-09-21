package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * A builder for {@link HeaderObject} objects
 */
public class HeaderObjectBuilder {
    private @Nullable Map<String, Object> extensions;
    private @Nullable String description;
    private @Nullable Boolean required;
    private @Nullable Boolean deprecated;
    private @Nullable String style;
    private @Nullable Boolean explode;
    private @Nullable SchemaObject schema;
    private @Nullable Object example;
    private @Nullable Map<String, ReferenceOr<ExampleObject>> examples;
    private @Nullable Map<String, MediaTypeObject> content;

    /**
     *
     * @param description A brief description of the header. This could contain examples of use.
     *                    <a href="http://spec.commonmark.org/">CommonMark syntax</a> MAY be used for rich text representation.
     *
     * @return The current builder
     */
    public HeaderObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     *
     * @param required Determines whether this header is mandatory. The default value is <code>false</code>.
     *
     * @return The current builder
     */
    public HeaderObjectBuilder withRequired(@Nullable Boolean required) {
        this.required = required;
        return this;
    }

    /**
     *
     * @param deprecated Specifies that a header is deprecated and SHOULD be transitioned out of usage.
     *
     * @return The current builder
     */
    public HeaderObjectBuilder withDeprecated(@Nullable Boolean deprecated) {
        this.deprecated = deprecated;
        return this;
    }

    /**
     *
     * @param style <p>Describes how the parameter value will be serialized depending on the type of the parameter value.
     *              Default value is <code>simple</code>.</p>
     *              <p>In order to support common ways of serializing simple parameters, a set of <code>style</code> values are defined.</p>
     *              <table>
     *              <caption>Style values</caption>
     *              <thead>
     *              <tr>
     *              <th><code>style</code></th>
     *              <th><code>type</code></th>
     *              <th>Comments</th>
     *              </tr>
     *              </thead>
     *              <tbody>
     *              <tr>
     *              <td>simple</td>
     *              <td><code>array</code></td>
     *              <td>Simple style parameters defined by <a href="https://tools.ietf.org/html/rfc6570#section-3.2.2">RFC6570</a>.  This option replaces <code>collectionFormat</code> with a <code>csv</code> value from OpenAPI 2.0.</td>
     *              </tr>
     *              </tbody>
     *              </table>
     *
     * @return The current builder
     */
    public HeaderObjectBuilder withStyle(@Nullable String style) {
        this.style = style;
        return this;
    }

    /**
     *
     * @param explode When this is true, parameter values of type <code>array</code> or <code>object</code> generate
     *                separate parameters for each value of the array or key-value pair of the map.  For other types
     *                of parameters this property has no effect. Headers use simple style, whose default is <code>false</code>.
     *
     * @return The current builder
     */
    public HeaderObjectBuilder withExplode(@Nullable Boolean explode) {
        this.explode = explode;
        return this;
    }

    /**
     *
     * @param schema The schema defining the type used for the header.
     *
     * @return The current builder
     */
    public HeaderObjectBuilder withSchema(@Nullable SchemaObject schema) {
        this.schema = schema;
        return this;
    }

    /**
     *
     * @param example Example of the media type.  The example SHOULD match the specified schema and encoding properties
     *                if present.  The <code>example</code> field is mutually exclusive of the <code>examples</code>
     *                field.  Furthermore, if referencing a <code>schema</code> which contains an example, the
     *                <code>example</code> value SHALL <em>override</em> the example provided by the schema.
     *                To represent examples of media types that cannot naturally be represented in JSON or YAML,
     *                a string value can contain the example with escaping where necessary.
     *
     * @return The current builder
     */
    public HeaderObjectBuilder withExample(@Nullable Object example) {
        this.example = example;
        return this;
    }

    /**
     *
     * @param examples Examples of the media type.  Each example SHOULD contain a value in the correct format as
     *                 specified in the parameter encoding.  The <code>examples</code> field is mutually exclusive
     *                 of the <code>example</code> field.  Furthermore, if referencing a <code>schema</code> which
     *                 contains an example, the <code>examples</code> value SHALL <em>override</em> the example
     *                 provided by the schema.
     *
     * @return The current builder
     */
    public HeaderObjectBuilder withExamples(@Nullable Map<String, ExampleObject> examples) {
        this.examples = ReferenceValues.inline(examples);
        return this;
    }

    /**
     *
     * @param content A map containing the representations for the parameter. The key is the media type and the value describes it.
     *                The map MUST only contain one entry.
     *
     * @return The current builder
     */
    public HeaderObjectBuilder withContent(@Nullable Map<String, MediaTypeObject> content) {
        this.content = content;
        return this;
    }

    /**
     * @return A new object
     */
    public HeaderObject build() {
        return new HeaderObject(description, required, deprecated, style, explode, schema, example,
            immutable(examples), immutable(content), extensions);
    }

    /**
     * Creates a builder for a {@link HeaderObject}
     *
     * @return A new builder
     */
    public static HeaderObjectBuilder headerObject() {
        return new HeaderObjectBuilder();
    }
    /**
     * @param value the extensions value
     * @return this builder */
    public HeaderObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public HeaderObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
    /**
     * @param value inline values and references for examples
     * @return this builder */
    public HeaderObjectBuilder withExamplesOrReferences(@Nullable Map<String, ReferenceOr<ExampleObject>> value) { this.examples = value; return this; }
}
