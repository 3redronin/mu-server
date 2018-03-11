package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.OpenAPIDocument.append;

public class License implements JsonWriter {

    public final String name;
    public final URI url;

    public License(String name, URI url) {
        notNull("name", name);
        this.name = name;
        this.url = url;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write("{");
        boolean isFirst = true;
        isFirst = !append(writer, "name", name, isFirst);
        isFirst = !append(writer, "url", url, isFirst);
        writer.write("}");
    }
}
