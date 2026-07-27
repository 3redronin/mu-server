package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;

import static io.muserver.openapi.Jsonizer.append;

/**
 * Identifies the contact person or organization for an API.
 *
 * @see ContactObjectBuilder
 */
public class ContactObject implements JsonWriter {
    private final @Nullable String name;
    private final @Nullable URI url;
    private final @Nullable String email;

    ContactObject(@Nullable String name, @Nullable URI url, @Nullable String email) {
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
        writer.write("}");
    }

    /**
     * Returns the contact name.
     *
     * @return The value described by {@link ContactObjectBuilder#withName}
     */
    public @Nullable String name() {
        return name;
    }

    /**
     * Returns the contact URL.
     *
     * @return The value described by {@link ContactObjectBuilder#withUrl}
     */
    public @Nullable URI url() {
        return url;
    }

    /**
     * Returns the contact email address.
     *
     * @return The value described by {@link ContactObjectBuilder#withEmail}
     */
    public @Nullable String email() {
        return email;
    }
}
