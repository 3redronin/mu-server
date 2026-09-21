package io.muserver.openapi;

import java.util.Map;

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
    private final @Nullable String identifier;
    private final Map<String, Object> extensions;

    private final String name;
    private final @Nullable URI url;

    LicenseObject(@Nullable String name, @Nullable URI url, @Nullable String identifier, @Nullable Map<String, Object> extensions) {
        if (identifier != null && url != null) throw new IllegalArgumentException("License identifier and url are mutually exclusive");
        this.identifier = identifier;
        this.extensions = Extensions.copy(extensions);
        notNull("name", name);
        this.name = java.util.Objects.requireNonNull(name);
        this.url = url;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "name", name, isFirst);
        isFirst = append(writer, "url", url, isFirst);
        isFirst = Jsonizer.append(writer, "identifier", identifier, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
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
    /** @return the identifier value */
    public @Nullable String identifier() { return identifier; }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public LicenseObjectBuilder toBuilder() {
        return new LicenseObjectBuilder()
            .withIdentifier(identifier).withExtensions(extensions).withName(name).withUrl(url);
    }
}
