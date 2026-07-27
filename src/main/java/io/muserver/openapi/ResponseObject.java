package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * Describes an OpenAPI response.
 *
 * @see ResponseObjectBuilder
 */
public class ResponseObject implements JsonWriter {

    private final String description;
    private final @Nullable Map<String, HeaderObject> headers;
    private final @Nullable Map<String, MediaTypeObject> content;
    private final @Nullable Map<String, LinkObject> links;

    ResponseObject(@Nullable String description, @Nullable Map<String, HeaderObject> headers, @Nullable Map<String, MediaTypeObject> content, @Nullable Map<String, LinkObject> links) {
        notNull("description", description);
        this.description = java.util.Objects.requireNonNull(description);
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
     * Gets the response description.
     *
     * @return the value described by {@link ResponseObjectBuilder#withDescription}
     */
    public String description() {
        return description;
    }

    /**
     * Gets the response headers.
     *
     * @return the value described by {@link ResponseObjectBuilder#withHeaders}
     */
    public @Nullable Map<String, HeaderObject> headers() {
        return headers;
    }

    /**
     * Gets the response content definitions.
     *
     * @return the value described by {@link ResponseObjectBuilder#withContent}
     */
    public @Nullable Map<String, MediaTypeObject> content() {
        return content;
    }

    /**
     * Gets the links that can be followed from the response.
     *
     * @return the value described by {@link ResponseObjectBuilder#withLinks}
     */
    public @Nullable Map<String, LinkObject> links() {
        return links;
    }
}
