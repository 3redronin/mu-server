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

    /**
     * @return A license with name "Apache 2.0" and URL <a href="http://www.apache.org/licenses/LICENSE-2.0.html">http://www.apache.org/licenses/LICENSE-2.0.html</a>
     */
    public static LicenseObject Apache2_0() {
        return licenseObject().withName("Apache 2.0").withUrl(URI.create("http://www.apache.org/licenses/LICENSE-2.0.html")).build();
    }

    /**
     * @return A license with name "MIT License" and URL <a href="https://opensource.org/licenses/mit-license.php">https://opensource.org/licenses/mit-license.php</a>
     */
    public static LicenseObject MITLicense() {
        return licenseObject().withName("MIT License").withUrl(URI.create("https://opensource.org/licenses/mit-license.php")).build();
    }

    /**
     * @return A license with name "The Unlicense" and URL <a href="https://unlicense.org">https://unlicense.org</a>
     */
    public static LicenseObject TheUnlicense() {
        return licenseObject().withName("The Unlicense").withUrl(URI.create("https://unlicense.org")).build();
    }
}