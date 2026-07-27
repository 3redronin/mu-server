package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

/**
 * Describes the representation of a resource for a media type.
 *
 * @see MediaTypeObjectBuilder
 */
public class MediaTypeObject implements JsonWriter {

    private final @Nullable SchemaObject schema;
    private final @Nullable Object example;
    private final @Nullable Map<String, ExampleObject> examples;
    private final @Nullable Map<String, EncodingObject> encoding;

    MediaTypeObject(@Nullable SchemaObject schema, @Nullable Object example, @Nullable Map<String, ExampleObject> examples, @Nullable Map<String, EncodingObject> encoding) {
        if (example != null && examples != null) {
            throw new IllegalArgumentException("Only one of 'example' and 'examples' can be supplied");
        }
        this.schema = schema;
        this.example = example;
        this.examples = examples;
        this.encoding = encoding;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.append('{');
        boolean isFirst = true;
        isFirst = Jsonizer.append(writer, "schema", schema, isFirst);
        isFirst = Jsonizer.append(writer, "example", example, isFirst);
        isFirst = Jsonizer.append(writer, "examples", examples, isFirst);
        isFirst = Jsonizer.append(writer, "encoding", encoding, isFirst);
        writer.append('}');
    }

    /**
     * Gets the schema for the media type.
     *
     * @return the value described by {@link MediaTypeObjectBuilder#withSchema}
     */
    public @Nullable SchemaObject schema() {
        return schema;
    }

    /**
     * Gets the example value for the media type.
     *
     * @return the value described by {@link MediaTypeObjectBuilder#withExample}
     */
    public @Nullable Object example() {
        return example;
    }

    /**
     * Gets the named examples for the media type.
     *
     * @return the value described by {@link MediaTypeObjectBuilder#withExamples}
     */
    public @Nullable Map<String, ExampleObject> examples() {
        return examples;
    }

    /**
     * Gets the encoding metadata for the media type.
     *
     * @return the value described by {@link MediaTypeObjectBuilder#withEncoding}
     */
    public @Nullable Map<String, EncodingObject> encoding() {
        return encoding;
    }
}
