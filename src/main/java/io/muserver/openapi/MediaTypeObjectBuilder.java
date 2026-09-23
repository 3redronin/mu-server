package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * Each Media Type Object provides schema and examples for the media type identified by its key.
 */
public class MediaTypeObjectBuilder {
    private @Nullable String description;
    private @Nullable SchemaObject itemSchema;
    private java.util.@Nullable List<EncodingObject> prefixEncoding;
    private @Nullable EncodingObject itemEncoding;
    private @Nullable Map<String, Object> extensions;
    private @Nullable SchemaObject schema;
    private @Nullable Object example;
    private @Nullable Map<String, ReferenceOr<ExampleObject>> examples;
    private @Nullable Map<String, EncodingObject> encoding;

    /**
     *
     * @param schema The schema defining the type used for the request body.
     *
     * @return The current builder
     */
    public MediaTypeObjectBuilder withSchema(@Nullable SchemaObject schema) {
        this.schema = schema;
        return this;
    }

    /**
     *
     * @param example Example of the media type.  The example object SHOULD be in the correct format as specified by the media type.
     *                The <code>example</code> field is mutually exclusive of the <code>examples</code> field.
     *
     * @return The current builder
     */
    public MediaTypeObjectBuilder withExample(@Nullable Object example) {
        this.example = example;
        return this;
    }

    /**
     *
     * @param examples Examples of the media type.  Each example object SHOULD  match the media type and specified schema if present.
     *                 The <code>examples</code> field is mutually exclusive of the <code>example</code> field.
     *
     * @return The current builder
     */
    public MediaTypeObjectBuilder withExamples(@Nullable Map<String, ExampleObject> examples) {
        this.examples = ReferenceValues.inline(examples);
        return this;
    }

    /**
     *
     * @param encoding A map between a property name and its encoding information. The key, being the property name, MUST
     *                 exist in the schema as a property. The encoding object SHALL only apply to <code>requestBody</code>
     *                 objects when the media type is <code>multipart</code> or <code>application/x-www-form-urlencoded</code>.
     *
     * @return The current builder
     */
    public MediaTypeObjectBuilder withEncoding(@Nullable Map<String, EncodingObject> encoding) {
        this.encoding = encoding;
        return this;
    }

    /**
     * @return A new object
     */
    public MediaTypeObject build() {
        return new MediaTypeObject(schema, example, immutable(examples), immutable(encoding), description, itemSchema, prefixEncoding, itemEncoding, extensions);
    }

    /**
     * Creates a builder for a {@link MediaTypeObject}
     *
     * @return A new builder
     */
    public static MediaTypeObjectBuilder mediaTypeObject() {
        return new MediaTypeObjectBuilder();
    }
    /**
     * @param value the extensions value
     * @return this builder */
    public MediaTypeObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public MediaTypeObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
    /**
     * @param value inline values and references for examples
     * @return this builder */
    public MediaTypeObjectBuilder withExamplesOrReferences(@Nullable Map<String, ReferenceOr<ExampleObject>> value) { this.examples = value; return this; }
    /** Combines alternative payload schemas, retaining the primary media type's annotations.
     *
     * @param primary first alternative
     *
     * @param secondary second alternative
     *
     * @return a builder for the combined media type */
    public static MediaTypeObjectBuilder mergeMediaTypes(MediaTypeObject primary, MediaTypeObject secondary) {
        SchemaObject a = primary.schema();
        SchemaObject b = secondary.schema();
        SchemaObject combined = null;
        if (a != null && b != null) {
            Map<String, SchemaObject> alternatives = new java.util.LinkedHashMap<>();
            for (SchemaObject schema : new SchemaObject[]{a, b}) {
                java.util.List<SchemaObject> choices = schema.keywords().size() == 1 && schema.anyOf() != null
                    ? java.util.Objects.requireNonNull(schema.anyOf()) : java.util.Collections.singletonList(schema);
                for (SchemaObject choice : choices) alternatives.putIfAbsent(choice.toString(), choice);
            }
            combined = alternatives.size() == 1 ? a : SchemaObjectBuilder.schemaObject().withAnyOf(new java.util.ArrayList<>(alternatives.values())).build();
        }
        SchemaObject itemA = primary.itemSchema();
        SchemaObject itemB = secondary.itemSchema();
        SchemaObject item = itemA == null ? itemB : itemB == null || itemA.toString().equals(itemB.toString()) ? itemA
            : SchemaObjectBuilder.schemaObject().withAnyOf(java.util.Arrays.asList(itemA, itemB)).build();
        return primary.toBuilder().withSchema(combined).withItemSchema(item);
    }
    /**
     * Describes this representation for API consumers. CommonMark formatting may be used.
     *
     * @param value the description of this media-type representation, or null to omit it
     * @return this builder
     */
    public MediaTypeObjectBuilder withDescription(@Nullable String value) { this.description = value; return this; }
    /**
     * Describes each parsed item in sequential content, such as an event stream or newline-delimited JSON.
     * This can coexist with {@link #withSchema(SchemaObject)}, which describes the entire body.
     * For server-sent events, the item describes a parsed event and its data field remains a string;
     * a JSON payload inside that string needs separate decoding.
     *
     * @param value the schema for each stream item, or null to omit it
     * @return this builder
     */
    public MediaTypeObjectBuilder withItemSchema(@Nullable SchemaObject value) { this.itemSchema = value; return this; }
    /**
     * Specifies how to encode the initial parts of multipart content by position.
     * Each list entry applies to the part at the same index. Remaining parts use
     * {@link #withItemEncoding(EncodingObject)} or the default encoding rules.
     * This cannot be combined with named {@link #withEncoding(Map)} entries.
     *
     * @param value the encodings for the initial multipart parts, in order, or null to omit it
     * @return this builder
     */
    public MediaTypeObjectBuilder withPrefixEncoding(java.util.@Nullable List<EncodingObject> value) { this.prefixEncoding = value; return this; }
    /**
     * Specifies the encoding for multipart parts after those covered by
     * {@link #withPrefixEncoding(java.util.List)}, or for all parts when no prefix is given.
     * This cannot be combined with named {@link #withEncoding(Map)} entries.
     *
     * @param value the encoding for remaining multipart parts, or null to omit it
     * @return this builder
     */
    public MediaTypeObjectBuilder withItemEncoding(@Nullable EncodingObject value) { this.itemEncoding = value; return this; }
}
