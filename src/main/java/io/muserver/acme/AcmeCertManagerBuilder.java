package io.muserver.acme;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>A builder for creating an {@link AcmeCertManagerImpl} by specifying the domain name, ACME URL, a directory
 * to place certificates, and other optional data.</p>
 * <p>To use Let's Encrypt, you can use the {@link #letsEncrypt()} convenience method.</p>
 */
public class AcmeCertManagerBuilder {

    private List<String> domains = new ArrayList<>();
    private File configDir;
    private URI acmeServerURI;
    private String organization;
    private String organizationalUnit;
    private String country;
    private String state;
    private boolean useNoOp = false;

    /**
     * @return Returns a manager that can create and renew HTTPS certificates.
     * @throws IllegalStateException Thrown if no config dir, ACME server, or domain name is set.
     */
    public AcmeCertManager build() {
        if (useNoOp) {
            return new NoOpAcmeCertManager();
        }

        if (configDir == null)
            throw new IllegalStateException("Please specify a configDir which is where certificates and keys will be kept");
        if (acmeServerURI == null)
            throw new IllegalStateException("Please specify an ACME Server URI.");
        if (domains.isEmpty()) {
            throw new IllegalStateException("Please specify a domain");
        }
        return new AcmeCertManagerImpl(configDir, acmeServerURI, organization, organizationalUnit, country, state, domains);
    }

    /**
     * Sets the domain that the certificate is for.
     * @param domain A domain name to request a certificate for, such as <code>my.example.org</code>
     * @return This builder
     */
    public AcmeCertManagerBuilder withDomain(String domain) {
        this.domains.add(domain);
        return this;
    }

    /**
     * <p>Sets a directory that will be used to store user keys and certificates.</p>
     * <p>This directory will hold your ACME account, private domain key, and the certificate so it is
     * recommended that this directory is backed up and kept secure.</p>
     * @param configDir a directory
     * @return This builder
     */
    public AcmeCertManagerBuilder withConfigDir(File configDir) {
        this.configDir = configDir;
        return this;
    }

    /**
     * <p>Sets a directory that will be used to store user keys and certificates.</p>
     * <p>This directory will hold your ACME account, private domain key, and the certificate so it is
     * recommended that this directory is backed up and kept secure.</p>
     * @param configDirPath a path to a directory
     * @return This builder
     */
    public AcmeCertManagerBuilder withConfigDir(String configDirPath) {
        return withConfigDir(new File(configDirPath));
    }

    /**
     * Sets the ACME server to use.
     * @param acmeServerURI The URL
     * @return This builder
     */
    public AcmeCertManagerBuilder withAcmeServerURI(URI acmeServerURI) {
        this.acmeServerURI = acmeServerURI;
        return this;
    }

    /**
     * Sets a value on the certificate signing request. This may or may not be set on the generated
     * certificate depending on the certificate authority used.
     * @param organization The organization requesting the cert
     * @return This builder
     */
    public AcmeCertManagerBuilder withOrganization(String organization) {
        this.organization = organization;
        return this;
    }

    /**
     * Sets a value on the certificate signing request. This may or may not be set on the generated
     * certificate depending on the certificate authority used.
     * @param organizationalUnit The unit in the organization requesting the cert
     * @return This builder
     */
    public AcmeCertManagerBuilder withOrganizationalUnit(String organizationalUnit) {
        this.organizationalUnit = organizationalUnit;
        return this;
    }

    /**
     * Sets a value on the certificate signing request. This may or may not be set on the generated
     * certificate depending on the certificate authority used.
     * @param country The country of the organization requesting the cert
     * @return This builder
     */
    public AcmeCertManagerBuilder withCountry(String country) {
        this.country = country;
        return this;
    }

    /**
     * Sets a value on the certificate signing request. This may or may not be set on the generated
     * certificate depending on the certificate authority used.
     * @param state The state the origanization requesting the cert resides in.
     * @return This builder
     */
    public AcmeCertManagerBuilder withState(String state) {
        this.state = state;
        return this;
    }

    /**
     * Makes it so a no-op version of the manager is returned. In this case, the start and stop methods
     * do nothing. Useful for disabling ACME integration during local development.
     * @param disabled If true, then no certs will be acquired and an unsigned cert will be used
     * @return This builder
     */
    public AcmeCertManagerBuilder disable(boolean disabled) {
        this.useNoOp = disabled;
        return this;
    }

    /**
     * @return Returns a new builder.
     */
    public static AcmeCertManagerBuilder acmeCertManager() {
        return new AcmeCertManagerBuilder();
    }

    /**
     * @return Returns a new cert manager builder using Let's Encrypt.
     */
    public static AcmeCertManagerBuilder letsEncrypt() {
        return acmeCertManager().withAcmeServerURI(URI.create("acme://letsencrypt.org/"));
    }

    /**
     * @return Returns a new cert manager builder using the Let's Encrypt staging environment.
     */
    public static AcmeCertManagerBuilder letsEncryptStaging() {
        return acmeCertManager().withAcmeServerURI(URI.create("acme://letsencrypt.org/staging"));
    }

    /**
     * @return Returns a builder that will do nothing. Useful for local development.
     */
    public static AcmeCertManagerBuilder noOpManager() {
        return acmeCertManager().disable(true);
    }
}
