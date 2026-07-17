package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see ResponseObjectBuilder
 */
public class ResponseObject implements JsonWriter {

    private final String description;
    private final @Nullable Map<String, HeaderObject> headers;
    private final @Nullable Map<String, MediaTypeObject> content;
    private final @Nullable Map<String, LinkObject> links;

    ResponseObject(String description, @Nullable Map<String, HeaderObject> headers, @Nullable Map<String, MediaTypeObject> content, @Nullable Map<String, LinkObject> links) {
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

    /**
     * @return the value described by {@link ResponseObjectBuilder#withDescription}
     */
    public String description() {
        return description;
    }

    /**
      @return the value described by {@link ResponseObjectBuilder#withHeaders}
     */
    public @Nullable Map<String, HeaderObject> headers() {
        return headers;
    }

    /**
      @return the value described by {@link ResponseObjectBuilder#withContent}
     */
    public @Nullable Map<String, MediaTypeObject> content() {
        return content;
    }

    /**
      @return the value described by {@link ResponseObjectBuilder#withLinks}
     */
    public @Nullable Map<String, LinkObject> links() {
        return links;
    }
}
