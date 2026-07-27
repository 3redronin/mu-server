package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * Each Media Type Object provides schema and examples for the media type identified by its key.
 */
public class MediaTypeObjectBuilder {
    private @Nullable SchemaObject schema;
    private @Nullable Object example;
    private @Nullable Map<String, ExampleObject> examples;
    private @Nullable Map<String, EncodingObject> encoding;

    /**
     * Creates an empty media type object builder.
     */
    public MediaTypeObjectBuilder() {
    }

    /**
     * Sets the schema for the media type.
     *
     * @param schema The schema defining the type used for the request body.
     * @return The current builder
     */
    public MediaTypeObjectBuilder withSchema(@Nullable SchemaObject schema) {
        this.schema = schema;
        return this;
    }

    /**
     * Sets an example value for the media type.
     *
     * @param example Example of the media type.  The example object SHOULD be in the correct format as specified by the media type.
     *                The <code>example</code> field is mutually exclusive of the <code>examples</code> field.
     * @return The current builder
     */
    public MediaTypeObjectBuilder withExample(@Nullable Object example) {
        this.example = example;
        return this;
    }

    /**
     * Sets the named examples for the media type.
     *
     * @param examples Examples of the media type.  Each example object SHOULD  match the media type and specified schema if present.
     *                 The <code>examples</code> field is mutually exclusive of the <code>example</code> field.
     * @return The current builder
     */
    public MediaTypeObjectBuilder withExamples(@Nullable Map<String, ExampleObject> examples) {
        this.examples = examples;
        return this;
    }

    /**
     * Sets encoding metadata for the media type.
     *
     * @param encoding A map between a property name and its encoding information. The key, being the property name, MUST
     *                 exist in the schema as a property. The encoding object SHALL only apply to <code>requestBody</code>
     *                 objects when the media type is <code>multipart</code> or <code>application/x-www-form-urlencoded</code>.
     * @return The current builder
     */
    public MediaTypeObjectBuilder withEncoding(@Nullable Map<String, EncodingObject> encoding) {
        this.encoding = encoding;
        return this;
    }

    /**
     * Builds a media type object from the configured values.
     *
     * @return A new object
     */
    public MediaTypeObject build() {
        return new MediaTypeObject(schema, example, immutable(examples), immutable(encoding));
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