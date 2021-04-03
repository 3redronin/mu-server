package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

/**
 * @see MediaTypeObjectBuilder
 */
public class MediaTypeObject implements JsonWriter {

    /**
     * @deprecated use {@link #schema()} instead
     */
    @Deprecated
    public final SchemaObject schema;
    /**
      @deprecated use {@link #example()} instead
     */
    @Deprecated
    public final Object example;
    /**
      @deprecated use {@link #examples()} instead
     */
    @Deprecated
    public final Map<String, ExampleObject> examples;
    /**
      @deprecated use {@link #encoding()} instead
     */
    @Deprecated
    public final Map<String, EncodingObject> encoding;

    MediaTypeObject(SchemaObject schema, Object example, Map<String, ExampleObject> examples, Map<String, EncodingObject> encoding) {
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
     * @return the value described by {@link MediaTypeObjectBuilder#withSchema}
     */
    public SchemaObject schema() {
        return schema;
    }

    /**
      @return the value described by {@link MediaTypeObjectBuilder#withExample}
     */
    public Object example() {
        return example;
    }

    /**
      @return the value described by {@link MediaTypeObjectBuilder#withExamples}
     */
    public Map<String, ExampleObject> examples() {
        return examples;
    }

    /**
      @return the value described by {@link MediaTypeObjectBuilder#withEncoding}
     */
    public Map<String, EncodingObject> encoding() {
        return encoding;
    }
}
