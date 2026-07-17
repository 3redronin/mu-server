package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see LicenseObjectBuilder
 */
public class LicenseObject implements JsonWriter {

    private final String name;
    private final @Nullable URI url;

    LicenseObject(String name, @Nullable URI url) {
        notNull("name", name);
        this.name = name;
        this.url = url;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "name", name, isFirst);
        isFirst = append(writer, "url", url, isFirst);
        writer.write('}');
    }

    /**
     * @return the value described by {@link LicenseObjectBuilder#withName}
     */
    public String name() {
        return name;
    }

    /**
      @return the value described by {@link LicenseObjectBuilder#withUrl}
     */
    public @Nullable URI url() {
        return url;
    }
}
