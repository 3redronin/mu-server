package io.muserver.openapi;


import java.io.IOException;
import java.io.Writer;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.OpenAPIDocument.append;

public class Info implements JsonWriter {

    public final String title;
    public final String description;
    public final String termsOfService;
    public final Contact contact;
    public final License license;
    public final String version;


    public Info(String title, String description, String termsOfService, Contact contact, License license, String version) {
        notNull("title", title);
        notNull("version", version);
        this.title = title;
        this.description = description;
        this.termsOfService = termsOfService;
        this.contact = contact;
        this.license = license;
        this.version = version;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write("{");
        boolean isFirst = true;
        isFirst = !append(writer, "title", title, isFirst);
        isFirst = !append(writer, "description", description, isFirst);
        isFirst = !append(writer, "termsOfService", termsOfService, isFirst);
        isFirst = !append(writer, "contact", contact, isFirst);
        isFirst = !append(writer, "license", license, isFirst);
        isFirst = !append(writer, "version", version, isFirst);
        writer.write("}");
    }
}
