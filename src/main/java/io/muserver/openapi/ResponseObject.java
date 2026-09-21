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
    private final Map<String, Object> extensions;

    private final String description;
    private final @Nullable Map<String, ReferenceOr<HeaderObject>> headers;
    private final @Nullable Map<String, MediaTypeObject> content;
    private final @Nullable Map<String, ReferenceOr<LinkObject>> links;

    ResponseObject(@Nullable String description, @Nullable Map<String, ReferenceOr<HeaderObject>> headers, @Nullable Map<String, MediaTypeObject> content, @Nullable Map<String, ReferenceOr<LinkObject>> links, @Nullable Map<String, Object> extensions) {
        this.extensions = Extensions.copy(extensions);
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
        isFirst = Extensions.write(writer, extensions, isFirst);
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
        return ReferenceValues.values(headers);
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
        return ReferenceValues.values(links);
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return inline values and references for headers */
    public @Nullable Map<String, ReferenceOr<HeaderObject>> headersOrReferences() { return headers; }
    /** @return inline values and references for links */
    public @Nullable Map<String, ReferenceOr<LinkObject>> linksOrReferences() { return links; }
    /** @return a builder preserving all fields and extensions */
    public ResponseObjectBuilder toBuilder() {
        return new ResponseObjectBuilder()
            .withExtensions(extensions).withDescription(description).withHeadersOrReferences(headers).withContent(content).withLinksOrReferences(links);
    }
}
