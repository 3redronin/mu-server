package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

/**
 * @see MediaTypeObjectBuilder
 */
public class MediaTypeObject implements JsonWriter {
    private final Map<String, Object> extensions;

    private final @Nullable SchemaObject schema;
    private final @Nullable Object example;
    private final @Nullable Map<String, ReferenceOr<ExampleObject>> examples;
    private final @Nullable Map<String, EncodingObject> encoding;

    MediaTypeObject(@Nullable SchemaObject schema, @Nullable Object example, @Nullable Map<String, ReferenceOr<ExampleObject>> examples, @Nullable Map<String, EncodingObject> encoding, @Nullable Map<String, Object> extensions) {
        this.extensions = Extensions.copy(extensions);
        if (example != null && examples != null) {
            throw new IllegalArgumentException("Only one of 'example' and 'examples' can be supplied");
        }
        this.schema = schema;
        this.example = example == null ? null : JsonValues.freeze(example);
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
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.append('}');
    }

    /**
     * @return the value described by {@link MediaTypeObjectBuilder#withSchema}
     */
    public @Nullable SchemaObject schema() {
        return schema;
    }

    /**
      @return the value described by {@link MediaTypeObjectBuilder#withExample}
     */
    public @Nullable Object example() {
        return example;
    }

    /**
      @return the value described by {@link MediaTypeObjectBuilder#withExamples}
     */
    public @Nullable Map<String, ExampleObject> examples() {
        return ReferenceValues.values(examples);
    }

    /**
      @return the value described by {@link MediaTypeObjectBuilder#withEncoding}
     */
    public @Nullable Map<String, EncodingObject> encoding() {
        return encoding;
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return inline values and references for examples */
    public @Nullable Map<String, ReferenceOr<ExampleObject>> examplesOrReferences() { return examples; }
    /** @return a builder preserving all fields and extensions */
    public MediaTypeObjectBuilder toBuilder() {
        return new MediaTypeObjectBuilder()
            .withExtensions(extensions).withSchema(schema).withExample(example).withExamplesOrReferences(examples).withEncoding(encoding);
    }
}
