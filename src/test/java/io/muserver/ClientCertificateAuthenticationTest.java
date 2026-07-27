package io.muserver;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.ClientUtils;
import scaffolding.MuAssert;
import scaffolding.ServerUtils;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.muserver.ClientCertificateAuthentication.MANDATORY;
import static io.muserver.ClientCertificateAuthentication.NONE;
import static io.muserver.ClientCertificateAuthentication.OPTIONAL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static scaffolding.ClientCertificateTestUtils.clientForcingCertificate;
import static scaffolding.ClientCertificateTestUtils.clientWithCertificate;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ClientCertificateAuthenticationTest {
    private MuServer server;

    @Test
    public void explicitNoneCannotBeCombinedWithATrustManager() {
        expectIllegalState(() -> HttpsConfigBuilder.unsignedLocalhost()
            .withClientCertificateTrustManager(ClientUtils.veryTrustingTrustManager())
            .withClientCertificateAuthentication(NONE)
            .build3());
    }

    @Test
    public void muBuiltSslContextsRequireATrustManagerForClientAuthentication() {
        expectIllegalState(() -> HttpsConfigBuilder.unsignedLocalhost()
            .withClientCertificateAuthentication(OPTIONAL)
            .build3());
        expectIllegalState(() -> HttpsConfigBuilder.unsignedLocalhost()
            .withClientCertificateAuthentication(MANDATORY)
            .build3());
    }

    @Test
    public void legacyTrustManagerStillEnablesOptionalAuthenticationWithASuppliedSslContext()
        throws Exception {
        AtomicBoolean embeddedTrustManagerWasCalled = new AtomicBoolean();
        HttpsConfigBuilder httpsConfig = new HttpsConfigBuilder()
            .withSSLContext(serverSslContext(acceptingTrustManager(embeddedTrustManagerWasCalled)))
            .withClientCertificateTrustManager(ClientUtils.veryTrustingTrustManager());
        server = serverWith(httpsConfig);

        try (Response response = call(request(server.uri()))) {
            assertThat(response.body().string(), equalTo("Client certificate present: false"));
        }
        try (Response response = clientForcingCertificate("client.p12")
            .newCall(request(server.uri()).build()).execute()) {
            assertThat(response.body().string(), equalTo("Client certificate present: true"));
        }
        assertTrue(embeddedTrustManagerWasCalled.get(),
            "The supplied SSLContext's trust manager should validate the certificate");
    }

    @Test
    public void noneDoesNotRequestAClientCertificate() throws Exception {
        AtomicBoolean certificateWasRequested = new AtomicBoolean();
        server = serverWith(NONE, null);

        try (Response response = clientForcingCertificate(
            "client.p12", certificateWasRequested).newCall(request(server.uri()).build()).execute()) {
            assertThat(response.body().string(), equalTo("Client certificate present: false"));
        }
        assertFalse(certificateWasRequested.get(),
            "The server should not have requested a client certificate");
    }

    @Test
    public void optionalAcceptsMissingCertificatesAndExposesValidCertificates() throws Exception {
        server = serverWith(OPTIONAL, ClientUtils.veryTrustingTrustManager());

        try (Response response = call(request(server.uri()))) {
            assertThat(response.body().string(), equalTo("Client certificate present: false"));
        }
        try (Response response = clientWithCertificate("client.p12")
            .newCall(request(server.uri()).build()).execute()) {
            assertThat(response.body().string(), equalTo("Client certificate present: true"));
        }
    }

    @Test
    public void mandatoryRejectsMissingCertificatesAndExposesValidCertificates() throws Exception {
        server = serverWith(MANDATORY, ClientUtils.veryTrustingTrustManager());

        assertThat(server.httpsConfig().clientCertificateAuthentication(), is(MANDATORY));
        assertConnectionFails(ClientUtils.client);
        try (Response response = clientWithCertificate("client.p12")
            .newCall(request(server.uri()).build()).execute()) {
            assertThat(response.body().string(), equalTo("Client certificate present: true"));
        }
    }

    @Test
    public void optionalRejectsPresentedUntrustedCertificates() throws Exception {
        assertPresentedUntrustedCertificateIsRejected(OPTIONAL);
    }

    @Test
    public void mandatoryRejectsPresentedUntrustedCertificates() throws Exception {
        assertPresentedUntrustedCertificateIsRejected(MANDATORY);
    }

    @Test
    public void authenticationPolicyCanBeChangedForNewConnections() throws Exception {
        X509TrustManager trustManager = ClientUtils.veryTrustingTrustManager();
        server = serverWith(OPTIONAL, trustManager);
        try (Response response = call(request(server.uri()))) {
            assertThat(response.code(), equalTo(200));
        }

        server.changeHttpsConfig(httpsConfig(MANDATORY, trustManager).build3());
        OkHttpClient newConnection = ClientUtils.client.newBuilder()
            .connectionPool(new ConnectionPool())
            .build();
        assertConnectionFails(newConnection);
    }

    private void assertPresentedUntrustedCertificateIsRejected(
        ClientCertificateAuthentication authentication) throws Exception {
        AtomicBoolean certificateWasChecked = new AtomicBoolean();
        server = serverWith(authentication, rejectingTrustManager(certificateWasChecked));

        assertConnectionFails(clientForcingCertificate("client.p12"));
        assertTrue(certificateWasChecked.get(), "The offered certificate should have been checked");
    }

    private MuServer serverWith(ClientCertificateAuthentication authentication,
                                X509TrustManager trustManager) {
        return serverWith(httpsConfig(authentication, trustManager));
    }

    private MuServer serverWith(HttpsConfigBuilder httpsConfig) {
        return ServerUtils.httpsServerForTest()
            .withHttpsConfig(httpsConfig)
            .addHandler(Method.GET, "/", (request, response, pathParams) ->
                response.write("Client certificate present: "
                    + request.connection().clientCertificate().isPresent()))
            .start();
    }

    private static HttpsConfigBuilder httpsConfig(
        ClientCertificateAuthentication authentication, X509TrustManager trustManager) {
        HttpsConfigBuilder httpsConfig = HttpsConfigBuilder.unsignedLocalhost();
        if (trustManager != null) {
            httpsConfig.withClientCertificateTrustManager(trustManager);
        }
        httpsConfig.withClientCertificateAuthentication(authentication);
        return httpsConfig;
    }

    private void assertConnectionFails(OkHttpClient client) throws Exception {
        try (Response ignored = client.newCall(request(server.uri()).build()).execute()) {
            fail("Expected the TLS handshake to fail");
        } catch (IOException expected) {
            // Expected: client authentication failed during the TLS handshake.
        }
    }

    private static SSLContext serverSslContext(X509TrustManager trustManager) throws Exception {
        char[] password = "Very5ecure".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream inputStream = ClientCertificateAuthenticationTest.class.getResourceAsStream(
            "/io/muserver/resources/localhost.p12")) {
            keyStore.load(inputStream, password);
        }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), new X509TrustManager[]{trustManager}, null);
        return context;
    }

    private static X509TrustManager acceptingTrustManager(AtomicBoolean certificateWasChecked) {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                certificateWasChecked.set(true);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static X509TrustManager rejectingTrustManager(AtomicBoolean certificateWasChecked) {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
                certificateWasChecked.set(true);
                throw new CertificateException("Client certificate is not trusted");
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static void expectIllegalState(ThrowingRunnable action) {
        try {
            action.run();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // Expected.
        } catch (Exception e) {
            throw new AssertionError("Expected IllegalStateException", e);
        }
    }

    @AfterEach
    public void stopIt() {
        MuAssert.stopAndCheck(server);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
