package io.muserver.openapi;

import java.net.URI;

/**
 * License information for the exposed API.
 */
public class LicenseObjectBuilder {
    private String name;
    private URI url;

    /**
     * @param name <strong>REQUIRED</strong>. The license name used for the API.
     * @return The current builder
     */
    public LicenseObjectBuilder withName(String name) {
        this.name = name;
        return this;
    }

    /**
     * @param url A URL to the license used for the API.
     * @return The current builder
     */
    public LicenseObjectBuilder withUrl(URI url) {
        this.url = url;
        return this;
    }

    public LicenseObject build() {
        return new LicenseObject(name, url);
    }

    /**
     * Creates a builder for a {@link LicenseObject}
     *
     * @return A new builder
     */
    public static LicenseObjectBuilder licenseObject() {
        return new LicenseObjectBuilder();
    }

    public static LicenseObject Apache2_0() {
        return licenseObject().withName("Apache 2.0").withUrl(URI.create("http://www.apache.org/licenses/LICENSE-2.0.html")).build();
    }
}