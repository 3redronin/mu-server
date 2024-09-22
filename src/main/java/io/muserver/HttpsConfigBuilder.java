package io.muserver;

import io.netty.handler.ssl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.*;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;

/**
 * A builder for specifying HTTPS config.
 * <p>To use HTTPS in your server, create an HTTPS Config builder and pass it to {@link MuServerBuilder#withHttpsConfig(HttpsConfigBuilder)}</p>
 */
public class HttpsConfigBuilder {

    private static final Logger log = LoggerFactory.getLogger(HttpsConfigBuilder.class);
    private String[] protocols = null;
    private String keystoreType = "JKS";
    private char[] keystorePassword = new char[0];
    private char[] keyPassword = new char[0];
    private byte[] keystoreBytes;
    private SSLContext sslContext;
    private CipherSuiteFilter nettyCipherSuiteFilter;
    private SSLCipherFilter sslCipherFilter;
    private KeyManagerFactory keyManagerFactory;
    private String defaultAlias;

    /**
     * Only used by HttpsConfigBuilder
     */
    protected X509TrustManager trustManager;

    /**
     * The type of keystore, such as JKS, JCEKS, PKCS12, etc
     * @param keystoreType The type of keystore to load
     * @return This builder
     */
    public HttpsConfigBuilder withKeystoreType(String keystoreType) {
        this.keystoreType = keystoreType;
        return this;
    }

    /**
     * The password to use to get the key from the keystore
     * @param keyPassword The password
     * @return This builder
     */
    public HttpsConfigBuilder withKeyPassword(String keyPassword) {
        return withKeyPassword(keyPassword.toCharArray());
    }

    /**
     * The password to use to access the keystore
     * @param keystorePassword The password
     * @return This builder
     */
    public HttpsConfigBuilder withKeystorePassword(String keystorePassword) {
        return withKeystorePassword(keystorePassword.toCharArray());
    }

    /**
     * The password to use to get the key from the keystore
     * @param keyPassword The password
     * @return This builder
     */
    public HttpsConfigBuilder withKeyPassword(char[] keyPassword) {
        this.keyPassword = keyPassword;
        return this;
    }

    /**
     * The password to use to access the keystore
     * @param keystorePassword The password
     * @return This builder
     */
    public HttpsConfigBuilder withKeystorePassword(char[] keystorePassword) {
        this.keystorePassword = keystorePassword;
        return this;
    }

    /**
     * The pre-built SSL Context to use
     * @param sslContext an SSL context
     * @return This builder
     */
    HttpsConfigBuilder withSSLContext(SSLContext sslContext) {
        keyManagerFactory = null;
        this.sslContext = sslContext;
        return this;
    }

    /**
     * Sets the keystore to use
     * @param is The input stream of the keystore
     * @param closeAfter Whether or not this method should close the stream
     */
    protected void setKeystoreBytes(InputStream is, boolean closeAfter) {
        sslContext = null;
        keyManagerFactory = null;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            Mutils.copy(is, baos, 8192);
            this.keystoreBytes = baos.toByteArray();
        } catch (IOException e) {
            throw new MuException("Error while loading keystore", e);
        } finally {
            if (closeAfter) {
                try {
                    is.close();
                } catch (IOException e) {
                    log.warn("Error while closing stream after reading SSL input stream", e);
                }
            }
        }
    }

    /**
     * Loads a keystore from the given stream.
     * <p>Does not close the keystore afterwards.</p>
     *
     * @param keystoreStream A stream to a keystore
     * @return This builder
     */
    public HttpsConfigBuilder withKeystore(InputStream keystoreStream) {
        setKeystoreBytes(keystoreStream, false);
        return this;
    }

    /**
     * Specifies the keystore to use
     * @param file A file object pointing to the keystore
     * @return This builder
     */
    public HttpsConfigBuilder withKeystore(File file) {
        if (!file.isFile()) {
            throw new IllegalArgumentException(Mutils.fullPath(file) + " does not exist");
        }
        FileInputStream fis;
        try {
            fis = new FileInputStream(file);
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException("Could not open file", e);
        }
        setKeystoreBytes(fis, true);
        return this;
    }

    /**
     * Uses the given KeyStore for TLS.
     *
     * @param keystore The keystore to use
     * @param password The keystore password.
     * @return This builder
     */
    public HttpsConfigBuilder withKeystore(KeyStore keystore, char[] password) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            keystore.store(baos, password);
            try (ByteArrayInputStream inputStream = new ByteArrayInputStream(baos.toByteArray())) {
                return withKeystore(inputStream)
                    .withKeystorePassword(password)
                    .withKeyPassword(password);
            }
        } catch (Exception e) {
            throw new MuException("Error loading KeyStore into memory", e);
        }
    }

    /**
     * Loads a keystore from the classpath
     *
     * @param classpath A path to load a keystore from, for example <code>/mycert.p12</code>
     * @return This builder
     */
    public HttpsConfigBuilder withKeystoreFromClasspath(String classpath) {
        InputStream keystoreStream = HttpsConfigBuilder.class.getResourceAsStream(classpath);
        if (keystoreStream == null) {
            throw new IllegalArgumentException("Could not find " + classpath);
        }
        setKeystoreBytes(keystoreStream, true);
        return this;
    }

    /**
     * Sets the key manager factory to use for SSL.
     * <p>Note this is an alternative to setting a keystore directory.</p>
     *
     * @param keyManagerFactory The key manager factory to use
     * @return This builder
     */
    public HttpsConfigBuilder withKeyManagerFactory(KeyManagerFactory keyManagerFactory) {
        this.keystoreBytes = null;
        this.sslContext = null;
        this.keyManagerFactory = keyManagerFactory;
        return this;
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
    public HttpsConfigBuilder withCipherFilter(SSLCipherFilter cipherFilter) {
        this.sslCipherFilter = cipherFilter;
        if (cipherFilter == null) {
            this.nettyCipherSuiteFilter = null;
        } else {
            this.nettyCipherSuiteFilter = (ciphers, defaultCiphers, supportedCiphers)
                -> {
                List<String> selected = cipherFilter.selectCiphers(supportedCiphers, defaultCiphers);
                if (selected == null) {
                    selected = defaultCiphers;
                }
                return selected.toArray(new String[0]);
            };
        }
        return this;
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
    public HttpsConfigBuilder withProtocols(String... protocols) {
        this.protocols = protocols;
        return this;
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
    public HttpsConfigBuilder withDefaultAlias(String certAlias) {
        this.defaultAlias = certAlias;
        return this;
    }

    /**
     * @return Creates an SSLContext
     * @deprecated Pass this builder itself to the HttpsConfig rather than building an SSLContext
     */
    @Deprecated
    SSLContext build() {
        if (keystoreBytes == null) {
            throw new MuException("No keystore has been set");
        }
        ByteArrayInputStream keystoreStream = new ByteArrayInputStream(keystoreBytes);
        try {
            SSLContext serverContext = SSLContext.getInstance("TLS");

            final KeyStore ks = KeyStore.getInstance(keystoreType);
            ks.load(keystoreStream, keystorePassword);

            final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, keyPassword);

            var tm = trustManager == null ? null : new TrustManager[] { trustManager };
            serverContext.init(kmf.getKeyManagers(), tm, null);
            return serverContext;
        } catch (Exception e) {
            throw new MuException("Error while setting up SSLContext", e);
        } finally {
            try {
                keystoreStream.close();
            } catch (IOException e) {
                log.info("Error while closing keystore stream: " + e.getMessage());
            }
        }
    }

    HttpsConfig build3() {
        SSLContext context = build();

        SSLParameters params = context.getDefaultSSLParameters();
        params.setUseCipherSuitesOrder(true);

        if (sslCipherFilter != null) {
            SSLServerSocketFactory ssf = context.getServerSocketFactory();
            List<String> selected = sslCipherFilter.selectCiphers(Set.of(ssf.getSupportedCipherSuites()), List.of(ssf.getDefaultCipherSuites()));
            String[] ciphersToUse = selected.toArray(new String[0]);
            params.setCipherSuites(ciphersToUse);
        }

        String[] protocolsToUse = getHttpsProtocolsArray(context);
        params.setProtocols(protocolsToUse);
        return new HttpsConfig(context, params, trustManager);
    }

    static Map<String, String> buildSanToAliasMap(KeyStore keyStore) throws KeyStoreException {
        Map<String, String> sanToAliasMap = new HashMap<>();
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            Certificate certificate = keyStore.getCertificate(alias);
            if (certificate instanceof X509Certificate) {
                List<String> sans = getDNSSubjectAlternativeNames((X509Certificate) certificate);
                for (String san : sans) {
                    sanToAliasMap.put(san, alias);
                }
            }
        }
        return sanToAliasMap;
    }

    static List<String> getDNSSubjectAlternativeNames(X509Certificate cert) {
        try {
            Collection<List<?>> subjectAlternativeNames = cert.getSubjectAlternativeNames();
            if (subjectAlternativeNames == null) {
                return Collections.emptyList();
            }
            return subjectAlternativeNames
                .stream()
                .filter(objects -> (objects.size() == 2)
                    && (objects.get(0) instanceof Integer)
                    && (objects.get(1) instanceof String)
                    && objects.get(0).equals(2)) // If type is 2, then we've got a dnsName
                .map(objects -> (String) objects.get(1))
                .collect(Collectors.toList());
        } catch (CertificateParsingException e) {
            log.warn("Can't get subject alternative names from cert {}", cert);
            return Collections.emptyList();
        }
    }

    SslContext toNettySslContext(boolean http2) throws Exception {
        CipherSuiteFilter cipherFilter = nettyCipherSuiteFilter != null ? nettyCipherSuiteFilter : IdentityCipherSuiteFilter.INSTANCE;

        SslContextBuilder builder;
        ClientAuth clientAuthSetting = trustManager == null ? ClientAuth.NONE : ClientAuth.OPTIONAL;
        if (sslContext != null) {
            return new JdkSslContext(sslContext, false, null, cipherFilter, ApplicationProtocolConfig.DISABLED, clientAuthSetting, getHttpsProtocolsArray(), false);
        } else if (keystoreBytes != null) {
            ByteArrayInputStream keystoreStream = new ByteArrayInputStream(keystoreBytes);
            KeyManagerFactory kmf;
            String defaultAliasToUse = this.defaultAlias;
            Map<String, String> sanToAliasMap = new HashMap<>();
            try {
                KeyStore ks = KeyStore.getInstance(keystoreType);
                ks.load(keystoreStream, keystorePassword);
                if (defaultAliasToUse == null) {
                    Enumeration<String> aliases = ks.aliases();
                    if (aliases.hasMoreElements()) {
                        defaultAliasToUse = aliases.nextElement();
                    }
                }
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, keyPassword);
                sanToAliasMap.putAll(buildSanToAliasMap(ks));
                log.debug("keystore san to alias mapping: {}", sanToAliasMap);
            } finally {
                try {
                    keystoreStream.close();
                } catch (IOException e) {
                    log.info("Error while closing keystore stream: " + e.getMessage());
                }
            }

            X509ExtendedKeyManager x509KeyManager = null;
            for (KeyManager keyManager : kmf.getKeyManagers()) {
                if (keyManager instanceof X509ExtendedKeyManager) {
                    x509KeyManager = (X509ExtendedKeyManager) keyManager;
                }
            }
            if (x509KeyManager == null)
                throw new Exception("KeyManagerFactory did not create an X509ExtendedKeyManager");
            SniKeyManager sniKeyManager = new SniKeyManager(x509KeyManager, defaultAliasToUse, sanToAliasMap);
            builder = SslContextBuilder.forServer(sniKeyManager);
        } else if (keyManagerFactory != null) {
            builder = SslContextBuilder.forServer(keyManagerFactory);
        } else {
            throw new IllegalStateException("No SSL info");
        }

        if (http2) {
            builder.applicationProtocolConfig(new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN, ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1
            ));
        }

        String[] protocolsArray = getHttpsProtocolsArray();

        if (trustManager != null) {
            builder.trustManager(trustManager);
        }
        return builder
            .clientAuth(clientAuthSetting)
            .protocols(protocolsArray)
            .ciphers(null, cipherFilter)
            .build();
    }

    private String[] getHttpsProtocolsArray() throws NoSuchAlgorithmException {
        return getHttpsProtocolsArray(SSLContext.getDefault());

    }
    private String[] getHttpsProtocolsArray(SSLContext sslContext) {
        List<String> supportedProtocols = asList(sslContext.getSupportedSSLParameters().getProtocols());
        List<String> protocolsToUse = new ArrayList<>();
        for (String protocol : Mutils.coalesce(this.protocols, new String[]{"TLSv1.2", "TLSv1.3"})) {
            if (supportedProtocols.contains(protocol)) {
                protocolsToUse.add(protocol);
            } else {
                log.warn("Will not use " + protocol + " as it is not supported by the current SSLContext");
            }
        }
        if (protocolsToUse.isEmpty()) {
            throw new MuException("Cannot start up as none of the requested SSL protocols " + Arrays.toString(this.protocols)
                + " are supported by the current SSLContext " + supportedProtocols);
        }
        return protocolsToUse.toArray(new String[0]);
    }


    /**
     * @return a new HttpsConfig builder
     */
    public static HttpsConfigBuilder httpsConfig() {
        return new HttpsConfigBuilder();
    }


    /**
     * Sets the trust manager that is used to validate client certificates.
     * <p>Setting the trust manager will make client certificates optional. The trust manager should
     * contain the public keys of certificate authorities that you want to allow client certificates
     * from.
     * Certificates will be available for request handlers at {@link HttpConnection#clientCertificate()}
     * (note that the connection of a request is available on {@link MuRequest#connection()}).</p>
     * <p><strong>Important note:</strong> if no certificate is set then the client certificate will be
     * <code>null</code>. If an invalid certificate is sent then the TLS connection will be rejected.</p>
     * @param trustManager The trust manager to use to validate client certificates
     * @return This builder.
     */
    public HttpsConfigBuilder withClientCertificateTrustManager(TrustManager trustManager) {
        if (trustManager != null && !(trustManager instanceof X509TrustManager)) {
            throw new IllegalArgumentException("Only X509 trust managers are supported");
        }
        this.trustManager = (X509TrustManager) trustManager;
        return this;
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
