package io.muserver;

import javax.net.ssl.KeyManagerFactory;
import java.io.File;
import java.io.InputStream;

/**
 * A builder for specifying HTTPS config.
 * <p>To use HTTPS in your server, create an HTTPS Config builder and pass it to {@link MuServerBuilder#withHttpsConfig(HttpsConfigBuilder)}</p>
 */
public class HttpsConfigBuilder extends SSLContextBuilder {

    /**
     * @return a new HttpsConfig builder
     */
    public static HttpsConfigBuilder httpsConfig() {
        return new HttpsConfigBuilder();
    }

    /**
     * The type of keystore, such as JKS, JCEKS, PKCS12, etc
     * @param keystoreType The type of keystore to load
     * @return This builder
     */
    @Override
    public HttpsConfigBuilder withKeystoreType(String keystoreType) {
        return (HttpsConfigBuilder) super.withKeystoreType(keystoreType);
    }

    /**
     * The password to use to get the key from the keystore
     * @param keyPassword The password
     * @return This builder
     */
    @Override
    public HttpsConfigBuilder withKeyPassword(String keyPassword) {
        return (HttpsConfigBuilder) super.withKeyPassword(keyPassword);
    }

    /**
     * The password to use to access the keystore
     * @param keystorePassword The password
     * @return This builder
     */
    @Override
    public HttpsConfigBuilder withKeystorePassword(String keystorePassword) {
        return (HttpsConfigBuilder) super.withKeystorePassword(keystorePassword);
    }

    /**
     * The password to use to get the key from the keystore
     * @param keyPassword The password
     * @return This builder
     */
    @Override
    public HttpsConfigBuilder withKeyPassword(char[] keyPassword) {
        return (HttpsConfigBuilder) super.withKeyPassword(keyPassword);
    }

    /**
     * The password to use to access the keystore
     * @param keystorePassword The password
     * @return This builder
     */
    @Override
    public HttpsConfigBuilder withKeystorePassword(char[] keystorePassword) {
        return (HttpsConfigBuilder) super.withKeystorePassword(keystorePassword);
    }

    /**
     * Loads a keystore from the given stream.
     * <p>Does not close the keystore afterwards.</p>
     *
     * @param keystoreStream A stream to a keystore
     * @return This builder
     */
    @Override
    public HttpsConfigBuilder withKeystore(InputStream keystoreStream) {
        return (HttpsConfigBuilder) super.withKeystore(keystoreStream);
    }

    /**
     * Specifies the keystore to use
     * @param file A file object pointing to the keystore
     * @return This builder
     */
    @Override
    public HttpsConfigBuilder withKeystore(File file) {
        return (HttpsConfigBuilder) super.withKeystore(file);
    }

    /**
     * Loads a keystore from the classpath
     *
     * @param classpath A path to load a keystore from, for example <code>/mycert.p12</code>
     * @return This builder
     */
    @Override
    public HttpsConfigBuilder withKeystoreFromClasspath(String classpath) {
        return (HttpsConfigBuilder) super.withKeystoreFromClasspath(classpath);
    }

    /**
     * Sets the key manager factory to use for SSL.
     * <p>Note this is an alternative to setting a keystore directory.</p>
     *
     * @param keyManagerFactory The key manager factory to use
     * @return This builder
     */
    @Override
    public HttpsConfigBuilder withKeyManagerFactory(KeyManagerFactory keyManagerFactory) {
        return (HttpsConfigBuilder) super.withKeyManagerFactory(keyManagerFactory);
    }

    /**
     * This option may be useful for cases where multiple certificates exist in a single keystore. For clients
     * that support it, SNI will be used to pick the correct certificate, however if the SNI is not used then
     * by default the first cert from the keystore will be picked. To override this default behaviour, you can
     * specify the certificate to use here when SNI is not available.
     * <p>Note you do not need to set this if your keystore has only one certificate in it.</p>
     * @param certAlias The alias of the cert to pick when SNI isn't available, or null to allow an arbitrary
     *                  cert to be picked (normally the first one).
     * @return This builder
     */
    @Override
    public HttpsConfigBuilder withDefaultAlias(String certAlias) {
        return (HttpsConfigBuilder) super.withDefaultAlias(certAlias);
    }

    /**
     * Sets a filter allowing you to specify which ciphers you would like to support.
     *
     * @param cipherFilter A Filter that takes all the supported ciphers, and all the default ciphers
     *                     (normally the default will exclude insecure ciphers that technically could
     *                     be supported) and returns a list of ciphers you want to use in your preferred
     *                     order.
     * @return This builder
     */
    @Override
    public HttpsConfigBuilder withCipherFilter(SSLCipherFilter cipherFilter) {
        return (HttpsConfigBuilder) super.withCipherFilter(cipherFilter);
    }

    /**
     * Sets the SSL/TLS protocols to use, for example "SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3".
     * The default is "TLSv1.2" and "TLSv1.3".
     * <p>Note that if the current JDK does not support a requested protocol then it will be ignored.
     * If no requested protocols are available, then an exception will be started when this is built.</p>
     *
     * @param protocols The protocols to use, or null to use the default.
     * @return This builder.
     */
    @Override
    public HttpsConfigBuilder withProtocols(String... protocols) {
        return (HttpsConfigBuilder) super.withProtocols(protocols);
    }

    /**
     * Creates an SSL config builder that will serve HTTPS over a self-signed SSL cert for the localhost domain.
     * <p>As no clients should trust this cert, this should be used only for testing purposes.</p>
     *
     * @return An HTTPS Config builder
     */
    public static HttpsConfigBuilder unsignedLocalhost() {
        // The cert was created with the following command:
        // keytool -genkeypair -keystore localhost.p12 -storetype PKCS12 -storepass Very5ecure -alias muserverlocalhost -keyalg RSA -sigalg SHA256withRSA -keysize 2048 -validity 36500 -dname "CN=Mu Server Test Cert, OU=Mu Server, O=Ronin" -ext san=dns:localhost,ip:127.0.0.1
        return httpsConfig()
            .withKeystoreType("PKCS12")
            .withKeystorePassword("Very5ecure")
            .withKeyPassword("Very5ecure")
            .withKeystoreFromClasspath("/io/muserver/resources/localhost.p12");
    }

}
