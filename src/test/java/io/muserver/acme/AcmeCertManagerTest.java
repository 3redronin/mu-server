package io.muserver.acme;

import io.muserver.Method;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import okhttp3.Response;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import java.io.File;
import java.net.URI;
import java.security.KeyPair;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.*;
import static scaffolding.ClientUtils.veryTrustingTrustManager;

public class AcmeCertManagerTest {

    private final File configDir = new File("target/testdata/" + UUID.randomUUID());
    private MuServer server;

    @Test
    public void canCreateKeyPairs() throws Exception {
        KeyPair created = AcmeCertManager.loadOrCreateKeypair(new File(configDir, "acme-account-key.pem"));
        KeyPair loaded = AcmeCertManager.loadOrCreateKeypair(new File(configDir, "acme-account-key.pem"));
        assertThat(loaded.getPrivate(), equalTo(created.getPrivate()));
        assertThat(loaded.getPublic(), equalTo(created.getPublic()));
    }


    @Test
    public void theVersionCanBeGotten() {
        assertThat(AcmeCertManager.artifactVersion(), containsString("."));
    }

    @Test
    public void selfSignedCertsUsedIfNoBetterOneAvailable() throws Exception {
        AcmeCertManager certManager = AcmeCertManagerBuilder.letsEncryptStaging()
            .withDomain("mu.example.org")
            .withConfigDir(configDir)
            .build();

        server = MuServerBuilder.httpsServer()
            .withHttpsConfig(certManager.createSSLContext())
            .addHandler(certManager.createHandler())
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.write("Hello from HTTPS");
            })
            .start();

        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.body().string(), equalTo("Hello from HTTPS"));
        }
        assertThat(certInformation(server.uri()), containsString("Subject: CN=Su Merver"));
    }

    @Test
    public void previousCertUsedIfAvailable() throws Exception {
        AcmeCertManager certManager = AcmeCertManagerBuilder.letsEncryptStaging()
            .withDomain("mu.example.org")
            .withConfigDir(new File("src/test/resources/example-config-dir"))
            .build();

        server = MuServerBuilder.httpsServer()
            .withHttpsConfig(certManager.createSSLContext())
            .addHandler(certManager.createHandler())
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.write("Hello from HTTPS");
            })
            .start();

        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.body().string(), equalTo("Hello from HTTPS"));
        }
        assertThat(certInformation(server.uri()), containsString("Subject: CN=muserver.io"));
    }

    @Test
    public void checksWillNotWorkIfDNSIsNotRight() throws Exception {
        AcmeCertManager certManager = AcmeCertManagerBuilder.letsEncryptStaging()
            .withDomain("mu.example.org")
            .withConfigDir(configDir)
            .build();

        server = MuServerBuilder.httpsServer().start();

        try {
            certManager.acquireCertIfNeeded(server);
            Assert.fail("Acquiring a cert shouldn't work here");
        } catch (CertificateOrderException ignored) {
            // Good!
        }
    }

    private static String certInformation(URI uri) throws Exception{
        SSLContext sslContext = sslContextForTesting(veryTrustingTrustManager());
        HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((s, sslSession) -> true);
        HttpsURLConnection conn = (HttpsURLConnection) uri.toURL().openConnection();
        conn.connect();
        Certificate[] certs = conn.getServerCertificates();
        StringBuilder sb = new StringBuilder();
        for (Certificate cert : certs) {
            sb.append("Certificate is: ").append(cert);
            if(cert instanceof X509Certificate) {
                X509Certificate x = (X509Certificate) cert;
                sb.append(x.getIssuerDN());
            }
        }
        return sb.toString();
    }

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}