package io.muserver.openapi;

import org.jspecify.annotations.Nullable;


import java.io.IOException;
import java.io.Writer;
import java.net.URI;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * Provides metadata about the API.
 *
 * @see InfoObjectBuilder
 */
public class InfoObject implements JsonWriter {

    private final String title;
    private final @Nullable String description;
    private final @Nullable URI termsOfService;
    private final @Nullable ContactObject contact;
    private final @Nullable LicenseObject license;
    private final String version;


    InfoObject(String title, @Nullable String description, @Nullable URI termsOfService,
               @Nullable ContactObject contact, @Nullable LicenseObject license, String version) {
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
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "title", title, isFirst);
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "termsOfService", termsOfService, isFirst);
        isFirst = append(writer, "contact", contact, isFirst);
        isFirst = append(writer, "license", license, isFirst);
        isFirst = append(writer, "version", version, isFirst);
        writer.write('}');
    }

    /**
     * Gets the API title.
     *
     * @return the value described by {@link InfoObjectBuilder#withTitle}
     */
    public String title() {
        return title;
    }

    /**
     * Gets the API description.
     *
     * @return the value described by {@link InfoObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
     * Gets the terms of service URI.
     *
     * @return the value described by {@link InfoObjectBuilder#withTermsOfService}
     */
    public @Nullable URI termsOfService() {
        return termsOfService;
    }

    /**
     * Gets the API contact details.
     *
     * @return the value described by {@link InfoObjectBuilder#withContact}
     */
    public @Nullable ContactObject contact() {
        return contact;
    }

    /**
     * Gets the API license details.
     *
     * @return the value described by {@link InfoObjectBuilder#withLicense}
     */
    public @Nullable LicenseObject license() {
        return license;
    }

    /**
     * Gets the OpenAPI document version.
     *
     * @return the value described by {@link InfoObjectBuilder#withVersion}
     */
    public String version() {
        return version;
    }
}
