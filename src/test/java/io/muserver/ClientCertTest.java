package io.muserver;

import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.ClientUtils;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.*;

public class ClientCertTest {

    private MuServer server;

    // The certs in `src/test/resources/client-certs` were created based on https://www.makethenmakeinstall.com/2014/05/ssl-client-authentication-step-by-step/

    @Test
    public void clientsCertsAreAvailableToHandlers() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .withHttpsConfig(HttpsConfigBuilder.unsignedLocalhost().withClientCertificateTrustManager(veryTrustingTrustManager))
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                X509Certificate cert = (X509Certificate) request.connection().clientCertificate().get();
                cert.checkValidity();
                response.write("The client cert is " + cert.getSubjectX500Principal().getName());
            })
            .start();
        OkHttpClient client = getClientWithCert("client.p12");
        try (Response resp = client.newCall(request(server.uri()).build()).execute()) {
            assertThat(resp.body().string(), equalTo("The client cert is CN=Moo Surfer Test User, O=Moo Surfer, L=London, C=UK"));
        }
    }

    @Test
    public void expiredCertsAreAvailable() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .withHttpsConfig(HttpsConfigBuilder.unsignedLocalhost().withClientCertificateTrustManager(veryTrustingTrustManager))
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                X509Certificate cert = (X509Certificate) request.connection().clientCertificate().get();
                try {
                    cert.checkValidity();
                    response.write("Expires " + cert.getNotAfter());
                } catch (CertificateExpiredException e) {
                    response.write("It has expired!");
                }
            })
            .start();
        OkHttpClient client = getClientWithCert("expired-client.p12");
        try (Response resp = client.newCall(request(server.uri()).build()).execute()) {
            assertThat(resp.body().string(), equalTo("It has expired!"));
        }
    }

    @Test
    public void clientsCertsAreOptional() throws Exception {
        server = ServerUtils.httpsServerForTest()
            .withHttpsConfig(HttpsConfigBuilder.unsignedLocalhost().withClientCertificateTrustManager(veryTrustingTrustManager))
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                Optional<Certificate> cert = request.connection().clientCertificate();
                response.write("The client cert is present? " + cert.isPresent());
            })
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.body().string(), equalTo("The client cert is present? false"));
        }
    }

    @Test
    public void certsAreNotAvailableOverHttp() throws Exception {
        server = MuServerBuilder.httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                boolean present = request.connection().clientCertificate().isPresent();
                response.write("The client cert is present? " + present);
            })
            .start();
        OkHttpClient client = getClientWithCert("client.p12");
        try (Response resp = client.newCall(request(server.uri()).build()).execute()) {
            assertThat(resp.body().string(), equalTo("The client cert is present? false"));
        }
    }


    @Test
    public void untrustedCertsThrowExceptions() throws Exception {
        // The default trust manager will not trust the cert created with a custom CA
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init((KeyStore)null);
        server = ServerUtils.httpsServerForTest()
            .withHttpsConfig(HttpsConfigBuilder.unsignedLocalhost()
                .withClientCertificateTrustManager(trustManagerFactory.getTrustManagers()[0])
            )
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                boolean present = request.connection().clientCertificate().isPresent();
                response.write("Cert is present? " + present);
            })
            .start();
        OkHttpClient client = getClientWithCert("client.p12");
        try (Response resp = client.newCall(request(server.uri()).build()).execute()) {
            assertThat(resp.body().string(), equalTo("Cert is present? false"));
        }
    }

    private static OkHttpClient getClientWithCert(String certFilename) throws Exception {
        SSLContext sslContext = ClientUtils.getPKCS12Context("/client-certs/" + certFilename, "export password");
        return new OkHttpClient.Builder()
            .sslSocketFactory(sslContext.getSocketFactory(), ClientUtils.veryTrustingTrustManager())
            .build();
    }

    @AfterEach
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }

}
