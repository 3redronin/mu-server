package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;

import static io.muserver.openapi.Jsonizer.append;

/**
 * @see ContactObjectBuilder
 */
public class ContactObject implements JsonWriter {
    private final Map<String, Object> extensions;
    private final @Nullable String name;
    private final @Nullable URI url;
    private final @Nullable String email;

    ContactObject(@Nullable String name, @Nullable URI url, @Nullable String email, @Nullable Map<String, Object> extensions) {
        this.extensions = Extensions.copy(extensions);
        if (email != null && !email.contains("@")) {
            throw new IllegalArgumentException("'email' must be a valid email address, but was " + email);
        }
        this.name = name;
        this.url = url;
        this.email = email;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write("{");
        boolean isFirst = true;
        isFirst = append(writer, "name", name, isFirst);
        isFirst = append(writer, "url", url, isFirst);
        isFirst = append(writer, "email", email, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write("}");
    }

    /**
     * @return The value described by {@link ContactObjectBuilder#withName}
     */
    public @Nullable String name() {
        return name;
    }

    /**
     * @return The value described by {@link ContactObjectBuilder#withUrl}
     */
    public @Nullable URI url() {
        return url;
    }

    /**
     * @return The value described by {@link ContactObjectBuilder#withEmail}
     */
    public @Nullable String email() {
        return email;
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public ContactObjectBuilder toBuilder() {
        return new ContactObjectBuilder()
            .withExtensions(extensions).withName(name).withUrl(url).withEmail(email);
    }
}
