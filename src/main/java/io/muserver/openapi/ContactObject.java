package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;

import static io.muserver.openapi.Jsonizer.append;

/**
 * @see ContactObjectBuilder
 */
public class ContactObject implements JsonWriter {
    /**
     * Use {@link #name()} instead
     */
    @Deprecated
    public final String name;
    /**
     * Use {@link #url()} instead
     */
    @Deprecated
    public final URI url;
    /**
     * Use {@link #email()} instead
     */
    @Deprecated
    public final String email;

    ContactObject(String name, URI url, String email) {
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
     * @return The value described by {@link ContactObjectBuilder#withName}
     */
    public String name() {
        return name;
    }

    /**
     * @return The value described by {@link ContactObjectBuilder#withUrl}
     */
    public URI url() {
        return url;
    }

    /**
     * @return The value described by {@link ContactObjectBuilder#withEmail}
     */
    public String email() {
        return email;
    }
}
