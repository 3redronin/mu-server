package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see ExternalDocumentationObjectBuilder
 */
public class ExternalDocumentationObject implements JsonWriter {
    public final String description;
    public final URI url;

    ExternalDocumentationObject(String description, URI url) {
        notNull("url", url);
        this.description = description;
        this.url = url;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "url", url, isFirst);
        writer.write('}');
    }
}
