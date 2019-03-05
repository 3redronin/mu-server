package io.muserver.acme;

import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.UUID;

class PemSslContextFactory {

    static KeyManagerFactory getKeyManagerFactory(File certFile, File privateKeyFile) throws IOException, CertificateException, KeyStoreException, NoSuchAlgorithmException, UnrecoverableKeyException {
        String keyPassword = UUID.randomUUID().toString();

        Collection<X509Certificate> cert = loadX509Certificate(certFile);

        Key key;
        try (PEMParser pemParser = new PEMParser(new FileReader(privateKeyFile))) {
            PEMKeyPair pemKeyPair = (PEMKeyPair) pemParser.readObject();
            KeyPair kp = new JcaPEMKeyConverter().getKeyPair(pemKeyPair);
            key = kp.getPrivate();
        }

        KeyStore keystore = KeyStore.getInstance("JKS");
        keystore.load(null);
        for (X509Certificate x509Certificate : cert) {
            keystore.setCertificateEntry("server", x509Certificate);
        }
        keystore.setKeyEntry("key-alias", key, keyPassword.toCharArray(), cert.toArray(new Certificate[0]));

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keystore, keyPassword.toCharArray());
        return kmf;
    }

    static Collection<X509Certificate> loadX509Certificate(File certFile) throws IOException, CertificateException {
        Collection<X509Certificate> cert;
        try (FileInputStream fis = new FileInputStream(certFile)) {
            CertificateFactory fact = CertificateFactory.getInstance("X.509");
            cert = (Collection<X509Certificate>) fact.generateCertificates(fis);
        }
        return cert;
    }

}
