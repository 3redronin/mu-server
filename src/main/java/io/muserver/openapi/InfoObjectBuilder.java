package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * The object provides metadata about the API. The metadata MAY be used by the clients if needed, and MAY be presented
 * in editing or documentation generation tools for convenience.
 */
public class InfoObjectBuilder {
    private @Nullable String summary;
    private @Nullable Map<String, Object> extensions;
    private String title = "API Documentation";
    private @Nullable String description;
    private @Nullable URI termsOfService;
    private @Nullable ContactObject contact;
    private @Nullable LicenseObject license;
    private String version = "1.0";

    /**
     *
     * @param title <strong>REQUIRED</strong>. The title of the application. Default value is <code>API Documentation</code>
     *
     * @return The current builder
     */
    public InfoObjectBuilder withTitle(String title) {
        this.title = title;
        return this;
    }

    /**
     *
     * @param description A short description of the application. <a href="http://spec.commonmark.org/">CommonMark syntax</a>
     *                    MAY be used for rich text representation.
     *
     * @return The current builder
     */
    public InfoObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     *
     * @param termsOfService A URL to the Terms of Service for the API.
     *
     * @return The current builder
     */
    public InfoObjectBuilder withTermsOfService(@Nullable URI termsOfService) {
        this.termsOfService = termsOfService;
        return this;
    }

    /**
     *
     * @param contact The contact information for the exposed API.
     *
     * @return The current builder
     */
    public InfoObjectBuilder withContact(@Nullable ContactObject contact) {
        this.contact = contact;
        return this;
    }

    /**
     *
     * @param license The license information for the exposed API.
     *
     * @return The current builder
     */
    public InfoObjectBuilder withLicense(@Nullable LicenseObject license) {
        this.license = license;
        return this;
    }

    /**
     *
     * @param version <strong>REQUIRED</strong>. The version of the OpenAPI document. Default value is <code>1.0</code>
     *
     * @return The current builder
     */
    public InfoObjectBuilder withVersion(String version) {
        this.version = version;
        return this;
    }

    /**
     * @return A new object
     */
    public InfoObject build() {
        return new InfoObject(title, description, termsOfService, contact, license, version, summary, extensions);
    }

    /**
     * Creates a builder for a {@link InfoObject}
     *
     * @return A new builder
     */
    public static InfoObjectBuilder infoObject() {
        return new InfoObjectBuilder();
    }
    /**
     * @param value the summary value
     * @return this builder */
    public InfoObjectBuilder withSummary(@Nullable String value) { this.summary = value; return this; }
    /**
     * @param value the extensions value
     * @return this builder */
    public InfoObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public InfoObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
}
