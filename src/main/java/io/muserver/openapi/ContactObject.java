package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;

import static io.muserver.openapi.Jsonizer.append;

/**
 * @see ContactObjectBuilder
 */
public class ContactObject implements JsonWriter {
    public final String name;
    public final URI url;
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
}
