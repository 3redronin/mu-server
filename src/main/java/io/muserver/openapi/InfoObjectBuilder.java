package io.muserver.openapi;

import java.net.URI;

/**
 * The object provides metadata about the API. The metadata MAY be used by the clients if needed, and MAY be presented
 * in editing or documentation generation tools for convenience.
 */
public class InfoObjectBuilder {
    private String title = "API Documentation";
    private String description;
    private URI termsOfService;
    private ContactObject contact;
    private LicenseObject license;
    private String version = "1.0";

    /**
     * @param title <strong>REQUIRED</strong>. The title of the application. Default value is <code>API Documentation</code>
     * @return The current builder
     */
    public InfoObjectBuilder withTitle(String title) {
        this.title = title;
        return this;
    }

    /**
     * @param description A short description of the application. <a href="http://spec.commonmark.org/">CommonMark syntax</a>
     *                    MAY be used for rich text representation.
     * @return The current builder
     */
    public InfoObjectBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * @param termsOfService A URL to the Terms of Service for the API.
     * @return The current builder
     */
    public InfoObjectBuilder withTermsOfService(URI termsOfService) {
        this.termsOfService = termsOfService;
        return this;
    }

    /**
     * @param contact The contact information for the exposed API.
     * @return The current builder
     */
    public InfoObjectBuilder withContact(ContactObject contact) {
        this.contact = contact;
        return this;
    }

    /**
     * @param license The license information for the exposed API.
     * @return The current builder
     */
    public InfoObjectBuilder withLicense(LicenseObject license) {
        this.license = license;
        return this;
    }

    /**
     * @param version <strong>REQUIRED</strong>. The version of the OpenAPI document. Default value is <code>1.0</code>
     * @return The current builder
     */
    public InfoObjectBuilder withVersion(String version) {
        this.version = version;
        return this;
    }

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