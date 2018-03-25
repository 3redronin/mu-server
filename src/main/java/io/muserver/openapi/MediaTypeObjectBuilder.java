package io.muserver.openapi;

import java.util.Map;

/**
 * Each Media Type Object provides schema and examples for the media type identified by its key.
 */
public class MediaTypeObjectBuilder {
    private SchemaObject schema;
    private Object example;
    private Map<String, ExampleObject> examples;
    private Map<String, EncodingObject> encoding;

    /**
     * @param schema The schema defining the type used for the request body.
     * @return The current builder
     */
    public MediaTypeObjectBuilder withSchema(SchemaObject schema) {
        this.schema = schema;
        return this;
    }

    /**
     * @param example Example of the media type.  The example object SHOULD be in the correct format as specified by the media type.
     *                The <code>example</code> field is mutually exclusive of the <code>examples</code> field.
     * @return The current builder
     */
    public MediaTypeObjectBuilder withExample(Object example) {
        this.example = example;
        return this;
    }

    /**
     * @param examples Examples of the media type.  Each example object SHOULD  match the media type and specified schema if present.
     *                 The <code>examples</code> field is mutually exclusive of the <code>example</code> field.
     * @return The current builder
     */
    public MediaTypeObjectBuilder withExamples(Map<String, ExampleObject> examples) {
        this.examples = examples;
        return this;
    }

    /**
     * @param encoding A map between a property name and its encoding information. The key, being the property name, MUST
     *                 exist in the schema as a property. The encoding object SHALL only apply to <code>requestBody</code>
     *                 objects when the media type is <code>multipart</code> or <code>application/x-www-form-urlencoded</code>.
     * @return The current builder
     */
    public MediaTypeObjectBuilder withEncoding(Map<String, EncodingObject> encoding) {
        this.encoding = encoding;
        return this;
    }

    public MediaTypeObject build() {
        return new MediaTypeObject(schema, example, examples, encoding);
    }

    /**
     * Creates a builder for a {@link MediaTypeObject}
     *
     * @return A new builder
     */
    public static MediaTypeObjectBuilder mediaTypeObject() {
        return new MediaTypeObjectBuilder();
    }
}