package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * The object provides metadata about the API. The metadata MAY be used by the clients if needed, and MAY be presented
 * in editing or documentation generation tools for convenience.
 */
public class InfoObjectBuilder {
    private String title = "API Documentation";
    private @Nullable String description;
    private @Nullable URI termsOfService;
    private @Nullable ContactObject contact;
    private @Nullable LicenseObject license;
    private String version = "1.0";

    /**
     * Creates an empty info object builder.
     */
    public InfoObjectBuilder() {
    }

    /**
     * Sets the API title.
     *
     * @param title <strong>REQUIRED</strong>. The title of the application. Default value is <code>API Documentation</code>
     * @return The current builder
     */
    public InfoObjectBuilder withTitle(String title) {
        this.title = title;
        return this;
    }

    /**
     * Sets the API description.
     *
     * @param description A short description of the application. <a href="http://spec.commonmark.org/">CommonMark syntax</a>
     *                    MAY be used for rich text representation.
     * @return The current builder
     */
    public InfoObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     * Sets the terms of service URI.
     *
     * @param termsOfService A URL to the Terms of Service for the API.
     * @return The current builder
     */
    public InfoObjectBuilder withTermsOfService(@Nullable URI termsOfService) {
        this.termsOfService = termsOfService;
        return this;
    }

    /**
     * Sets the API contact details.
     *
     * @param contact The contact information for the exposed API.
     * @return The current builder
     */
    public InfoObjectBuilder withContact(@Nullable ContactObject contact) {
        this.contact = contact;
        return this;
    }

    /**
     * Sets the API license details.
     *
     * @param license The license information for the exposed API.
     * @return The current builder
     */
    public InfoObjectBuilder withLicense(@Nullable LicenseObject license) {
        this.license = license;
        return this;
    }

    /**
     * Sets the OpenAPI document version string.
     *
     * @param version <strong>REQUIRED</strong>. The version of the OpenAPI document. Default value is <code>1.0</code>
     * @return The current builder
     */
    public InfoObjectBuilder withVersion(String version) {
        this.version = version;
        return this;
    }

    /**
     * Builds an info object from the configured values.
     *
     * @return A new object
     */
    public InfoObject build() {
        return new InfoObject(title, description, termsOfService, contact, license, version);
    }

    /**
     * Creates a builder for a {@link InfoObject}
     *
     * @return A new builder
     */
    public static InfoObjectBuilder infoObject() {
        return new InfoObjectBuilder();
    }
}
