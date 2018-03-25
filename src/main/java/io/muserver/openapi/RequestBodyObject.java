package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see RequestBodyObjectBuilder
 */
public class RequestBodyObject implements JsonWriter {

    public final String description;
    public final Map<String, MediaTypeObject> content;
    public final boolean required;

    RequestBodyObject(String description, Map<String, MediaTypeObject> content, boolean required) {
        notNull("content", content);
        this.description = description;
        this.content = content;
        this.required = required;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "content", content, isFirst);
        isFirst = append(writer, "required", required, isFirst);
        writer.write('}');
    }
}
