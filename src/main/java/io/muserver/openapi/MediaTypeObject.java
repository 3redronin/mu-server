package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

/**
 * @see MediaTypeObjectBuilder
 */
public class MediaTypeObject implements JsonWriter {

    public final SchemaObject schema;
    public final Object example;
    public final Map<String, ExampleObject> examples;
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
}
