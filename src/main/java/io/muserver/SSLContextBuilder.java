package io.muserver;

import io.netty.handler.ssl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.*;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public class SSLContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(SSLContextBuilder.class);
    private String[] protocols = null;
    private String keystoreType = "JKS";
    private char[] keystorePassword = new char[0];
    private char[] keyPassword = new char[0];
    private InputStream keystoreStream;
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

    public SSLContextBuilder withKeystore(InputStream keystoreStream) {
        sslContext = null;
        keyManagerFactory = null;
        this.keystoreStream = keystoreStream;
        return this;
    }

    public SSLContextBuilder withKeystore(File file) {
        sslContext = null;
        keyManagerFactory = null;
        if (!file.isFile()) {
            throw new IllegalArgumentException(Mutils.fullPath(file) + " does not exist");
        }
        try {
            this.keystoreStream = new FileInputStream(file);
        } catch (FileNotFoundException e) {
            throw new IllegalArgumentException("Could not open file", e);
        }
        return this;
    }

    public SSLContextBuilder withKeystoreFromClasspath(String classpath) {
        sslContext = null;
        keyManagerFactory = null;
        keystoreStream = SSLContextBuilder.class.getResourceAsStream(classpath);
        if (keystoreStream == null) {
            throw new IllegalArgumentException("Could not find " + classpath);
        }
        return this;
    }

    public SSLContextBuilder withKeyManagerFactory(KeyManagerFactory keyManagerFactory) {
        this.keystoreStream = null;
        this.sslContext = null;
        this.keyManagerFactory = keyManagerFactory;
        return this;
    }

    /**
     * Sets a filter allowing you to specify which ciphers you would like to support.
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
     * Sets the SSL/TLS protocols to use, for example "SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2".
     * The default is "TLSv1.2".
     * @param protocols The protocols to use, or null to use the default
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
        if (keystoreStream == null) {
            throw new MuException("No keystore has been set");
        }
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
            if (keystoreStream != null) {
                try {
                    keystoreStream.close();
                } catch (IOException e) {
                    log.info("Error while closing keystore stream: " + e.getMessage());
                }
            }
        }
    }

    SslContext toNettySslContext(boolean http2) throws Exception {
        SslContextBuilder builder;
        if (sslContext != null) {
            return new JdkSslContext(sslContext, false, ClientAuth.NONE);
        } else if (keystoreStream != null) {
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
        return builder
            .clientAuth(ClientAuth.NONE)
            .protocols(protocols)
            .ciphers(null, cipherFilter)
            .build();
    }

    public static SSLContextBuilder sslContext() {
        return new SSLContextBuilder();
    }

    public static SSLContext defaultSSLContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException e) {
            throw new MuException("Error while setting up SSLContext", e);
        }
    }

    public static SSLContext unsignedLocalhostCert() {
        return unsignedLocalhostCertBuilder().build();
    }
    public static SSLContextBuilder unsignedLocalhostCertBuilder() {
        // The cert was created with the following command:
        // keytool -genkeypair -keystore localhost.p12 -storetype PKCS12 -storepass Very5ecure -alias muserverlocalhost -keyalg RSA -sigalg SHA256withRSA -keysize 2048 -validity 36500 -dname "CN=Mu Server Test Cert, OU=Mu Server, O=Ronin" -ext san=dns:localhost,ip:127.0.0.1
        return sslContext()
            .withKeystoreType("PKCS12")
            .withKeystorePassword("Very5ecure")
            .withKeyPassword("Very5ecure")
            .withKeystoreFromClasspath("/io/muserver/resources/localhost.p12");
    }

}

