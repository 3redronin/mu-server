package io.muserver.openapi;


import java.io.IOException;
import java.io.Writer;
import java.net.URI;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see InfoObjectBuilder
 */
public class InfoObject implements JsonWriter {

    /**
     * @deprecated use {@link #title()} instead
     */
    @Deprecated
    public final String title;
    /**
      @deprecated use {@link #description()} instead
     */
    @Deprecated
    public final String description;
    /**
      @deprecated use {@link #termsOfService()} instead
     */
    @Deprecated
    public final URI termsOfService;
    /**
      @deprecated use {@link #contact()} instead
     */
    @Deprecated
    public final ContactObject contact;
    /**
      @deprecated use {@link #license()} instead
     */
    @Deprecated
    public final LicenseObject license;
    /**
      @deprecated use {@link #version()} instead
     */
    @Deprecated
    public final String version;


    InfoObject(String title, String description, URI termsOfService, ContactObject contact, LicenseObject license, String version) {
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
     * @return the value described by {@link InfoObjectBuilder#withTitle}
     */
    public String title() {
        return title;
    }

    /**
      @return the value described by {@link InfoObjectBuilder#withDescription}
     */
    public String description() {
        return description;
    }

    /**
      @return the value described by {@link InfoObjectBuilder#withTermsOfService}
     */
    public URI termsOfService() {
        return termsOfService;
    }

    /**
      @return the value described by {@link InfoObjectBuilder#withContact}
     */
    public ContactObject contact() {
        return contact;
    }

    /**
      @return the value described by {@link InfoObjectBuilder#withLicense}
     */
    public LicenseObject license() {
        return license;
    }

    /**
      @return the value described by {@link InfoObjectBuilder#withVersion}
     */
    public String version() {
        return version;
    }
}
