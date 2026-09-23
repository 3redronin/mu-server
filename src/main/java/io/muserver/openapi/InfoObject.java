package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;


import java.io.IOException;
import java.io.Writer;
import java.net.URI;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see InfoObjectBuilder
 */
public class InfoObject implements JsonWriter {
    private final @Nullable String summary;
    private final Map<String, Object> extensions;

    private final String title;
    private final @Nullable String description;
    private final @Nullable URI termsOfService;
    private final @Nullable ContactObject contact;
    private final @Nullable LicenseObject license;
    private final String version;


    InfoObject(String title, @Nullable String description, @Nullable URI termsOfService, @Nullable ContactObject contact, @Nullable LicenseObject license, String version, @Nullable String summary, @Nullable Map<String, Object> extensions) {
        this.summary = summary;
        this.extensions = Extensions.copy(extensions);
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
        isFirst = Jsonizer.append(writer, "summary", summary, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write('}');
    }

    /**
     * @return the value described by {@link InfoObjectBuilder#withTitle}
     */
    public String title() {
        return title;
    }

    /**
      @return the value described by {@link InfoObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
      @return the value described by {@link InfoObjectBuilder#withTermsOfService}
     */
    public @Nullable URI termsOfService() {
        return termsOfService;
    }

    /**
      @return the value described by {@link InfoObjectBuilder#withContact}
     */
    public @Nullable ContactObject contact() {
        return contact;
    }

    /**
      @return the value described by {@link InfoObjectBuilder#withLicense}
     */
    public @Nullable LicenseObject license() {
        return license;
    }

    /**
      @return the value described by {@link InfoObjectBuilder#withVersion}
     */
    public String version() {
        return version;
    }
    /** @return the summary value */
    public @Nullable String summary() { return summary; }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public InfoObjectBuilder toBuilder() {
        return new InfoObjectBuilder()
            .withSummary(summary).withExtensions(extensions).withTitle(title).withDescription(description).withTermsOfService(termsOfService).withContact(contact).withLicense(license).withVersion(version);
    }
}
