package io.muserver.openapi;

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
    private final @Nullable String description;
    private final URI url;

    ExternalDocumentationObject(@Nullable String description, @Nullable URI url) {
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
}
