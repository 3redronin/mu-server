package io.muserver;

import io.netty.handler.ssl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.*;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Arrays.asList;

/**
 * @deprecated Use {@link HttpsConfigBuilder} instead
 */
@Deprecated
public class SSLContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(SSLContextBuilder.class);
    private String[] protocols = null;
    private String keystoreType = "JKS";
    private char[] keystorePassword = new char[0];
    private char[] keyPassword = new char[0];
    private byte[] keystoreBytes;
    private SSLContext sslContext;
    private CipherSuiteFilter nettyCipherSuiteFilter;
    private KeyManagerFactory keyManagerFactory;

    public SSLContextBuilder withKeystoreType(String keystoreType) {
        this.keystoreType = keystoreType;
        return this;
    }

    public SSLContextBuilder withKeyPassword(String keyPassword) {
        return withKeyPassword(keyPassword.toCharArray());
    }

    public SSLContextBuilder withKeystorePassword(String keystorePassword) {
        return withKeystorePassword(keystorePassword.toCharArray());
    }

    public SSLContextBuilder withKeyPassword(char[] keyPassword) {
        this.keyPassword = keyPassword;
        return this;
    }

    SSLContextBuilder withSSLContext(SSLContext sslContext) {
        keyManagerFactory = null;
        this.sslContext = sslContext;
        return this;
    }

    public SSLContextBuilder withKeystorePassword(char[] keystorePassword) {
        this.keystorePassword = keystorePassword;
        return this;
    }

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
    public SSLContextBuilder withKeystore(InputStream keystoreStream) {
        setKeystoreBytes(keystoreStream, false);
        return this;
    }

    public SSLContextBuilder withKeystore(File file) {
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
     * Loads a keystore from the classpath
     *
     * @param classpath A path to load a keystore from, for example <code>/mycert.p12</code>
     * @return This builder
     */
    public SSLContextBuilder withKeystoreFromClasspath(String classpath) {
        InputStream keystoreStream = SSLContextBuilder.class.getResourceAsStream(classpath);
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
    public SSLContextBuilder withKeyManagerFactory(KeyManagerFactory keyManagerFactory) {
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
    public SSLContextBuilder withCipherFilter(SSLCipherFilter cipherFilter) {
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
    public SSLContextBuilder withProtocols(String... protocols) {
        this.protocols = protocols;
        return this;
    }

    /**
     * @return Creates an SSLContext
     * @deprecated Pass this builder itself to the HttpsConfig rather than building an SSLContext
     */
    @Deprecated
    public SSLContext build() {
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

            serverContext.init(kmf.getKeyManagers(), null, null);
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

    SslContext toNettySslContext(boolean http2) throws Exception {
        SslContextBuilder builder;
        if (sslContext != null) {
            return new JdkSslContext(sslContext, false, ClientAuth.NONE);
        } else if (keystoreBytes != null) {
            ByteArrayInputStream keystoreStream = new ByteArrayInputStream(keystoreBytes);
            KeyManagerFactory kmf;
            try {
                KeyStore ks = KeyStore.getInstance(keystoreType);
                ks.load(keystoreStream, keystorePassword);
                kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, keyPassword);
            } finally {
                try {
                    keystoreStream.close();
                } catch (IOException e) {
                    log.info("Error while closing keystore stream: " + e.getMessage());
                }
            }
            builder = SslContextBuilder.forServer(kmf);
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

        CipherSuiteFilter cipherFilter = nettyCipherSuiteFilter != null ? nettyCipherSuiteFilter : IdentityCipherSuiteFilter.INSTANCE;

        List<String> supportedProtocols = asList(SSLContext.getDefault().getSupportedSSLParameters().getProtocols());
        List<String> protocolsToUse = new ArrayList<>();
        for (String protocol : Mutils.coalesce(this.protocols, new String[]{"TLSv1.2", "TLSv1.3"})) {
            if (supportedProtocols.contains(protocol)) {
                protocolsToUse.add(protocol);
            } else {
                log.warn("Will not use " + protocol + " as it is not supported by the current JDK");
            }
        }
        if (protocolsToUse.isEmpty()) {
            throw new MuException("Cannot start up as none of the requested SSL protocols " + Arrays.toString(this.protocols)
                + " are supported by the current JDK " + supportedProtocols);
        }

        return builder
            .clientAuth(ClientAuth.NONE)
            .protocols(protocolsToUse.toArray(new String[0]))
            .ciphers(null, cipherFilter)
            .build();
    }

    /**
     * Use {@link HttpsConfigBuilder#httpsConfig()} instead
     *
     * @return This builder
     */
    @Deprecated
    public static SSLContextBuilder sslContext() {
        return new SSLContextBuilder();
    }

    @Deprecated
    public static SSLContext defaultSSLContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new MuException("Error while setting up SSLContext", e);
        }
    }

    /**
     * @deprecated  Use {@link HttpsConfigBuilder#unsignedLocalhost()} instead
     * @return An SSLContext
     */
    @Deprecated
    public static SSLContext unsignedLocalhostCert() {
        return unsignedLocalhostCertBuilder().build();
    }

    /**
     * @deprecated  Use {@link HttpsConfigBuilder#unsignedLocalhost()} instead
     * @return A builder
     */
    @Deprecated
    public static SSLContextBuilder unsignedLocalhostCertBuilder() {
        return HttpsConfigBuilder.unsignedLocalhost();
    }

}

