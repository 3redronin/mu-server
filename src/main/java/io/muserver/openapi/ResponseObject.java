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
    private final @Nullable String summary;
    private final Map<String, Object> extensions;

    private final @Nullable String description;
    private final @Nullable Map<String, ReferenceOr<HeaderObject>> headers;
    private final @Nullable Map<String, ReferenceOr<MediaTypeObject>> content;
    private final @Nullable Map<String, ReferenceOr<LinkObject>> links;

    ResponseObject(@Nullable String description, @Nullable Map<String, ReferenceOr<HeaderObject>> headers, @Nullable Map<String, ReferenceOr<MediaTypeObject>> content, @Nullable Map<String, ReferenceOr<LinkObject>> links, @Nullable String summary, @Nullable Map<String, Object> extensions) {
        this.summary = summary;
        this.extensions = Extensions.copy(extensions);
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
        isFirst = Jsonizer.append(writer, "summary", summary, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write('}');
    }

    /**
     * @return the value described by {@link ResponseObjectBuilder#withDescription}
     */
    public @Nullable String description() {
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
        return ReferenceValues.values(content);
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
            .withSummary(summary).withExtensions(extensions).withDescription(description).withHeadersOrReferences(headers).withContentOrReferences(content).withLinksOrReferences(links);
    }
    /**
     * @return the response summary, or null when omitted
     * @see ResponseObjectBuilder#withSummary
     */
    public @Nullable String summary() { return summary; }
    /** @return inline media types and references */
    public @Nullable Map<String, ReferenceOr<MediaTypeObject>> contentOrReferences() { return content; }
}
