package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see ResponseObjectBuilder
 */
public class ResponseObject implements JsonWriter {

    public final String description;
    public final Map<String, HeaderObject> headers;
    public final Map<String, MediaTypeObject> content;
    public final Map<String, LinkObject> links;

    ResponseObject(String description, Map<String, HeaderObject> headers, Map<String, MediaTypeObject> content, Map<String, LinkObject> links) {
        notNull("description", description);
        this.description = description;
        this.headers = headers;
        this.content = content;
        this.links = links;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "headers", headers, isFirst);
        isFirst = append(writer, "content", content, isFirst);
        isFirst = append(writer, "links", links, isFirst);
        writer.write('}');
    }
}
