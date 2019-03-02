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
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.UUID;

class PemSslContextFactory {

    static SSLContext getSSLContextFromLetsEncrypt(File certFile, File privateKeyFile) throws Exception {
        String keyPassword = UUID.randomUUID().toString();

        X509Certificate cert = loadX509Certificate(certFile);

        Key key;
        try (PEMParser pemParser = new PEMParser(new FileReader(privateKeyFile))) {
            PEMKeyPair pemKeyPair = (PEMKeyPair) pemParser.readObject();
            KeyPair kp = new JcaPEMKeyConverter().getKeyPair(pemKeyPair);
            key = kp.getPrivate();
        }


        KeyStore keystore = KeyStore.getInstance("JKS");
        keystore.load(null);
        keystore.setCertificateEntry("cert-alias", cert);
        keystore.setKeyEntry("key-alias", key, keyPassword.toCharArray(), new Certificate[]{cert});

        KeyManagerFactory kmf = KeyManagerFactory.getInstance("SunX509");
        kmf.init(keystore, keyPassword.toCharArray());

        KeyManager[] km = kmf.getKeyManagers();

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(km, null, null);
        return context;
    }

    static X509Certificate loadX509Certificate(File certFile) throws IOException, CertificateException {
        X509Certificate cert;
        try (FileInputStream fis = new FileInputStream(certFile)) {
            CertificateFactory fact = CertificateFactory.getInstance("X.509");
            cert = (X509Certificate) fact.generateCertificate(fis);
        }
        return cert;
    }


}
