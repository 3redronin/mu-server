package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see ExternalDocumentationObjectBuilder
 */
public class ExternalDocumentationObject implements JsonWriter {
    private final Map<String, Object> extensions;
    private final @Nullable String description;
    private final URI url;

    ExternalDocumentationObject(@Nullable String description, @Nullable URI url, @Nullable Map<String, Object> extensions) {
        this.extensions = Extensions.copy(extensions);
        this.description = description;
        notNull("url", url);
        this.url = java.util.Objects.requireNonNull(url);
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "url", url, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write('}');
    }

    /**
     * @return the value described by {@link ExternalDocumentationObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
      @return the value described by {@link ExternalDocumentationObjectBuilder#withUrl}
     */
    public URI url() {
        return url;
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public ExternalDocumentationObjectBuilder toBuilder() {
        return new ExternalDocumentationObjectBuilder()
            .withExtensions(extensions).withDescription(description).withUrl(url);
    }
}
