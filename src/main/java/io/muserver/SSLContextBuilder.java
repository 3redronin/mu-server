package io.muserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.*;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;

public class SSLContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(SSLContextBuilder.class);

    private String keystoreType = "JKS";
    private char[] keystorePassword = new char[0];
    private char[] keyPassword = new char[0];
    private InputStream keystoreStream;

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

    public SSLContextBuilder withKeystorePassword(char[] keystorePassword) {
        this.keystorePassword = keystorePassword;
        return this;
    }

    public SSLContextBuilder withKeystore(InputStream keystoreStream) {
        this.keystoreStream = keystoreStream;
        return this;
    }

    public SSLContextBuilder withKeystore(File file) {
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
        keystoreStream = SSLContextBuilder.class.getResourceAsStream(classpath);
        if (keystoreStream == null) {
            throw new IllegalArgumentException("Could not find " + classpath);
        }
        return this;
    }

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
        // The cert was created with the following command:
        // keytool -genkey -storetype JKS -keyalg RSA -sigalg SHA256withRSA -alias muserverlocalhost -keystore localhost.jks -validity 36500 -keysize 2048 -storepass Very5ecure -keypass ActuallyNotSecure
        return sslContext()
            .withKeystoreType("JKS")
            .withKeystorePassword("Very5ecure")
            .withKeyPassword("ActuallyNotSecure")
            .withKeystoreFromClasspath("/io/muserver/resources/localhost.jks")
            .build();
    }

}
