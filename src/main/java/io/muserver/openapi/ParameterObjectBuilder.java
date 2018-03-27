package io.muserver.openapi;

import java.util.Map;

/**
 * <p>Describes a single operation parameter.</p>
 * <p>A unique parameter is defined by a combination of a name and {@link ParameterObjectBuilder#withIn(String)} (location).</p>
 * <p>Parameter Locations</p>
 * <p>There are four possible parameter locations specified by the <code>in</code> field:</p>
 * <ul>
 * <li>path - Used together with Path Templating, where the parameter value is actually part of the operation's
 * URL. This does not include the host or base path of the API. For example, in <code>/items/{itemId}</code>,
 * the path parameter is <code>itemId</code>.</li>
 * <li>query - Parameters that are appended to the URL. For example, in <code>/items?id=###</code>, the query
 * parameter is <code>id</code>.</li>
 * <li>header - Custom headers that are expected as part of the request. Note that
 * <a href="https://tools.ietf.org/html/rfc7230#page-22">RFC7230</a> states header names are case insensitive.</li>
 * <li>cookie - Used to pass a specific cookie value to the API.</li>
 * </ul>
 */
public class ParameterObjectBuilder {
    private String name;
    private String in;
    private String description;
    private Boolean required;
    private boolean deprecated;
    private boolean allowEmptyValue;
    private String style;
    private Boolean explode;
    private boolean allowReserved;
    private SchemaObject schema;
    private Object example;
    private Map<String, ExampleObject> examples;
    private Map<String, MediaTypeObject> content;

    /**
     * @param name <strong>REQUIRED</strong>. The name of the parameter. Parameter names are <em>case sensitive</em>.
     *             <ul>
     *             <li>If <code>in</code> is <code>"path"</code>, the <code>name</code> field MUST correspond to
     *             the associated path segment from the path field in the {@link PathsObject}.</li>
     *             <li>If <code>in</code> is <code>"header"</code> and the <code>name</code> field is
     *             <code>"Accept"</code>, <code>"Content-Type"</code> or <code>"Authorization"</code>,
     *             the parameter definition SHALL be ignored.</li>
     *             <li>For all other cases, the <code>name</code> corresponds to the parameter name used by the
     *             <code>in</code> property.</li>
     *             </ul>
     * @return The current builder
     */
    public ParameterObjectBuilder withName(String name) {
        this.name = name;
        return this;
    }

    /**
     * @param in <strong>REQUIRED</strong>. The location of the parameter. Possible values are "query", "header", "path" or "cookie".
     * @return The current builder
     */
    public ParameterObjectBuilder withIn(String in) {
        this.in = in;
        return this;
    }

    /**
     * @param description A brief description of the parameter. This could contain examples of use.
     *                    <a href="http://spec.commonmark.org/">CommonMark syntax</a> MAY be used for rich text representation.
     * @return The current builder
     */
    public ParameterObjectBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * @param required Determines whether this parameter is mandatory. If the parameter location is "path",
     *                 this property is <strong>REQUIRED</strong> and its value MUST be <code>true</code>.
     *                 Otherwise, the property MAY be included and its default value is <code>false</code>.
     * @return The current builder
     */
    public ParameterObjectBuilder withRequired(Boolean required) {
        this.required = required;
        return this;
    }

    /**
     * @param deprecated Specifies that a parameter is deprecated and SHOULD be transitioned out of usage.
     * @return The current builder
     */
    public ParameterObjectBuilder withDeprecated(boolean deprecated) {
        this.deprecated = deprecated;
        return this;
    }

    /**
     * @param allowEmptyValue Sets the ability to pass empty-valued parameters. This is valid only for
     *                        <code>query</code> parameters and allows sending a parameter with an empty value.
     *                        Default value is <code>false</code>. If <code>style</code> is used, and if behavior
     *                        is <code>n/a</code> (cannot be serialized), the value of <code>allowEmptyValue</code>
     *                        SHALL be ignored.
     * @return The current builder
     */
    public ParameterObjectBuilder withAllowEmptyValue(boolean allowEmptyValue) {
        this.allowEmptyValue = allowEmptyValue;
        return this;
    }

    /**
     * @param style <p>Describes how the parameter value will be serialized depending on the type of the parameter value.
     *              Default values (based on value of <code>in</code>): for <code>query</code> - <code>form</code>;
     *              for <code>path</code> - <code>simple</code>; for <code>header</code> - <code>simple</code>;
     *              for <code>cookie</code> - <code>form</code>.</p>
     *              <p>In order to support common ways of serializing simple parameters, a set of <code>style</code> values are defined.</p>
     *              <table summary="Style values">
     *              <thead>
     *              <tr>
     *              <th><code>style</code></th>
     *              <th><code>type</code></th>
     *              <th><code>in</code></th>
     *              <th>Comments</th>
     *              </tr>
     *              </thead>
     *              <tbody>
     *              <tr>
     *              <td>matrix</td>
     *              <td><code>primitive</code>, <code>array</code>, <code>object</code></td>
     *              <td><code>path</code></td>
     *              <td>Path-style parameters defined by <a href="https://tools.ietf.org/html/rfc6570#section-3.2.7">RFC6570</a></td>
     *              </tr>
     *              <tr>
     *              <td>label</td>
     *              <td><code>primitive</code>, <code>array</code>, <code>object</code></td>
     *              <td><code>path</code></td>
     *              <td>Label style parameters defined by <a href="https://tools.ietf.org/html/rfc6570#section-3.2.5">RFC6570</a></td>
     *              </tr>
     *              <tr>
     *              <td>form</td>
     *              <td><code>primitive</code>, <code>array</code>, <code>object</code></td>
     *              <td><code>query</code>, <code>cookie</code></td>
     *              <td>Form style parameters defined by <a href="https://tools.ietf.org/html/rfc6570#section-3.2.8">RFC6570</a>. This option replaces <code>collectionFormat</code> with a <code>csv</code> (when <code>explode</code> is false) or <code>multi</code> (when <code>explode</code> is true) value from OpenAPI 2.0.</td>
     *              </tr>
     *              <tr>
     *              <td>simple</td>
     *              <td><code>array</code></td>
     *              <td><code>path</code>, <code>header</code></td>
     *              <td>Simple style parameters defined by <a href="https://tools.ietf.org/html/rfc6570#section-3.2.2">RFC6570</a>.  This option replaces <code>collectionFormat</code> with a <code>csv</code> value from OpenAPI 2.0.</td>
     *              </tr>
     *              <tr>
     *              <td>spaceDelimited</td>
     *              <td><code>array</code></td>
     *              <td><code>query</code></td>
     *              <td>Space separated array values. This option replaces <code>collectionFormat</code> equal to <code>ssv</code> from OpenAPI 2.0.</td>
     *              </tr>
     *              <tr>
     *              <td>pipeDelimited</td>
     *              <td><code>array</code></td>
     *              <td><code>query</code></td>
     *              <td>Pipe separated array values. This option replaces <code>collectionFormat</code> equal to <code>pipes</code> from OpenAPI 2.0.</td>
     *              </tr>
     *              <tr>
     *              <td>deepObject</td>
     *              <td><code>object</code></td>
     *              <td><code>query</code></td>
     *              <td>Provides a simple way of rendering nested objects using form parameters.</td>
     *              </tr>
     *              </tbody>
     *              </table>
     * @return The current builder
     */
    public ParameterObjectBuilder withStyle(String style) {
        this.style = style;
        return this;
    }

    /**
     * @param explode When this is true, parameter values of type <code>array</code> or <code>object</code> generate
     *                separate parameters for each value of the array or key-value pair of the map.  For other types
     *                of parameters this property has no effect. When <code>style</code> is <code>form</code>, the
     *                default value is <code>true</code>. For all other styles, the default value is <code>false</code>.
     * @return The current builder
     */
    public ParameterObjectBuilder withExplode(Boolean explode) {
        this.explode = explode;
        return this;
    }

    /**
     * @param allowReserved Determines whether the parameter value SHOULD allow reserved characters, as defined by
     *                      <a href="https://tools.ietf.org/html/rfc3986#section-2.2">RFC3986</a>
     *                      <code>:/?#[]@!$&amp;'()*+,;=</code> to be included without percent-encoding. This property
     *                      only applies to parameters with an <code>in</code> value of <code>query</code>. The
     *                      default value is <code>false</code>.
     * @return The current builder
     */
    public ParameterObjectBuilder withAllowReserved(boolean allowReserved) {
        this.allowReserved = allowReserved;
        return this;
    }

    /**
     * @param schema The schema defining the type used for the parameter.
     * @return The current builder
     */
    public ParameterObjectBuilder withSchema(SchemaObject schema) {
        this.schema = schema;
        return this;
    }

    /**
     * @param example Example of the media type.  The example SHOULD match the specified schema and encoding properties
     *                if present.  The <code>example</code> field is mutually exclusive of the <code>examples</code>
     *                field.  Furthermore, if referencing a <code>schema</code> which contains an example, the
     *                <code>example</code> value SHALL <em>override</em> the example provided by the schema.
     *                To represent examples of media types that cannot naturally be represented in JSON or YAML,
     *                a string value can contain the example with escaping where necessary.
     * @return The current builder
     */
    public ParameterObjectBuilder withExample(Object example) {
        this.example = example;
        return this;
    }

    /**
     * @param examples Examples of the media type.  Each example SHOULD contain a value in the correct format as
     *                 specified in the parameter encoding.  The <code>examples</code> field is mutually exclusive
     *                 of the <code>example</code> field.  Furthermore, if referencing a <code>schema</code> which
     *                 contains an example, the <code>examples</code> value SHALL <em>override</em> the example
     *                 provided by the schema.
     * @return The current builder
     */
    public ParameterObjectBuilder withExamples(Map<String, ExampleObject> examples) {
        this.examples = examples;
        return this;
    }

    /**
     * @param content A map containing the representations for the parameter. The key is the media type and the value describes it.
     *                The map MUST only contain one entry.
     * @return The current builder
     */
    public ParameterObjectBuilder withContent(Map<String, MediaTypeObject> content) {
        this.content = content;
        return this;
    }

    public ParameterObject build() {
        Boolean explodeVal = this.explode == null ? "form".equals(style) : this.explode;
        Boolean requiredVal = this.required == null ? "path".equals(in) : this.required;
        return new ParameterObject(name, in, description, requiredVal, deprecated, allowEmptyValue, style, explodeVal, allowReserved, schema, example, examples, content);
    }

    /**
     * Creates a builder for a {@link ParameterObject}
     *
     * @return A new builder
     */
    public static ParameterObjectBuilder parameterObject() {
        return new ParameterObjectBuilder();
    }
}