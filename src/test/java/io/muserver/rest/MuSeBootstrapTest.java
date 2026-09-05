package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.Test;
import org.junit.BeforeClass;
import scaffolding.ClientUtils;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static jakarta.ws.rs.SeBootstrap.Configuration.SSLClientAuthentication.MANDATORY;
import static jakarta.ws.rs.SeBootstrap.Configuration.SSLClientAuthentication.NONE;
import static jakarta.ws.rs.SeBootstrap.Configuration.SSLClientAuthentication.OPTIONAL;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static scaffolding.ClientCertificateTestUtils.clientForcingCertificate;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class MuSeBootstrapTest {

    @BeforeClass
    public static void registerRuntimeDelegate() {
        MuRuntimeDelegate.ensureSet();
    }

    @Test
    public void bootsApplicationAtConfiguredRootAndApplicationPaths() throws Exception {
        SeBootstrap.Configuration requested = SeBootstrap.Configuration.builder()
            .protocol("HTTP")
            .host("localhost")
            .port(SeBootstrap.Configuration.FREE_PORT)
            .rootPath("/root/path")
            .build();

        SeBootstrap.Instance instance = SeBootstrap.start(new TestApplication(), requested)
            .toCompletableFuture().get(10, TimeUnit.SECONDS);
        try {
            SeBootstrap.Configuration actual = instance.configuration();
            assertThat(actual.protocol(), is("HTTP"));
            assertThat(actual.host(), is("localhost"));
            assertThat(actual.port(), is(greaterThan(0)));
            assertThat(actual.rootPath(), is("/root/path"));
            try (Response response = call(request(actual.baseUriBuilder().path("application/resource").build()))) {
                assertThat(response.code(), is(200));
                assertThat(response.body().string(), is("booted"));
            }
            assertThat(instance.unwrap(MuServer.class).uri().getPort(), is(actual.port()));
        } finally {
            SeBootstrap.Instance.StopResult result = instance.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertNull(result.unwrap(Object.class));
        }
    }

    @Test
    public void configurationHasDefaultsAndLoadsKnownExternalProperties() {
        SeBootstrap.Configuration defaults = SeBootstrap.Configuration.builder().build();
        assertThat(defaults.protocol(), is("HTTP"));
        assertThat(defaults.host(), is("localhost"));
        assertThat(defaults.port(), is(SeBootstrap.Configuration.DEFAULT_PORT));
        assertThat(defaults.rootPath(), is("/"));
        assertThat(defaults.sslClientAuthentication(),
            is(SeBootstrap.Configuration.SSLClientAuthentication.NONE));

        SeBootstrap.Configuration loaded = SeBootstrap.Configuration.builder()
            .from((name, type) -> {
                if (SeBootstrap.Configuration.HOST.equals(name)) {
                    return Optional.of(type.cast("127.0.0.1"));
                }
                if (SeBootstrap.Configuration.PORT.equals(name)) {
                    return Optional.of(type.cast(12345));
                }
                return Optional.empty();
            })
            .property("unknown", "ignored")
            .build();
        assertThat(loaded.host(), is("127.0.0.1"));
        assertThat(loaded.port(), is(12345));
        assertThat(loaded.protocol(), is("HTTP"));
        assertNull(loaded.property("unknown"));
    }

    @Test
    public void applicationClassIsCreatedWithItsDefaultConstructor() throws Exception {
        SeBootstrap.Instance instance = SeBootstrap.start(TestApplication.class)
            .toCompletableFuture().get(10, TimeUnit.SECONDS);
        try {
            try (Response response = call(request(instance.configuration().baseUriBuilder()
                .path("application/resource").build()))) {
                assertThat(response.code(), is(200));
                assertThat(response.body().string(), is("booted"));
            }
        } finally {
            instance.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void suppliedApplicationIsAvailableAsContext() throws Exception {
        AtomicReference<Application> injected = new AtomicReference<>();
        @Path("application-context")
        class Resource {
            @GET
            public String get(@Context Application application) {
                injected.set(application);
                return "injected";
            }
        }
        Application application = new Application() {
            @Override
            public Set<Object> getSingletons() {
                return Set.of(new Resource());
            }
        };
        SeBootstrap.Configuration configuration = SeBootstrap.Configuration.builder()
            .port(SeBootstrap.Configuration.FREE_PORT)
            .build();

        SeBootstrap.Instance instance = SeBootstrap.start(application, configuration)
            .toCompletableFuture().get(10, TimeUnit.SECONDS);
        try {
            try (Response response = call(request(instance.configuration().baseUriBuilder()
                .path("application-context").build()))) {
                assertThat(response.code(), is(200));
                assertThat(response.body().string(), is("injected"));
            }
            assertThat(injected.get(), sameInstance(application));
        } finally {
            instance.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void httpsNoneDoesNotRequestAClientCertificate() throws Exception {
        AtomicBoolean certificateWasRequested = new AtomicBoolean();
        AtomicBoolean certificateWasChecked = new AtomicBoolean();
        SeBootstrap.Instance instance = startHttps(NONE, recordingTrustManager(certificateWasChecked, false));
        try {
            try (Response response = clientForcingCertificate("client.p12", certificateWasRequested)
                .newCall(request(resourceUri(instance)).build()).execute()) {
                assertThat(response.code(), is(200));
            }
            assertFalse("The server should not have requested a client certificate",
                certificateWasRequested.get());
            assertFalse("The server should not have checked a client certificate",
                certificateWasChecked.get());
        } finally {
            stop(instance);
        }
    }

    @Test
    public void httpsOptionalAcceptsMissingCertificatesAndValidatesPresentedCertificates() throws Exception {
        AtomicBoolean certificateWasChecked = new AtomicBoolean();
        SeBootstrap.Instance instance = startHttps(
            OPTIONAL, recordingTrustManager(certificateWasChecked, false));
        try {
            try (Response response = call(request(resourceUri(instance)))) {
                assertThat(response.code(), is(200));
            }
            assertFalse(certificateWasChecked.get());

            try (Response response = clientForcingCertificate("client.p12")
                .newCall(request(resourceUri(instance)).build()).execute()) {
                assertThat(response.code(), is(200));
            }
            assertTrue(certificateWasChecked.get());
        } finally {
            stop(instance);
        }
    }

    @Test
    public void httpsMandatoryRejectsMissingCertificatesAndAcceptsValidCertificates() throws Exception {
        AtomicBoolean certificateWasChecked = new AtomicBoolean();
        SeBootstrap.Instance instance = startHttps(
            MANDATORY, recordingTrustManager(certificateWasChecked, false));
        try {
            assertConnectionFails(instance, ClientUtils.client);

            try (Response response = clientForcingCertificate("client.p12")
                .newCall(request(resourceUri(instance)).build()).execute()) {
                assertThat(response.code(), is(200));
            }
            assertTrue(certificateWasChecked.get());
        } finally {
            stop(instance);
        }
    }

    @Test
    public void httpsOptionalRejectsPresentedUntrustedCertificates() throws Exception {
        assertPresentedUntrustedCertificateIsRejected(OPTIONAL);
    }

    @Test
    public void httpsMandatoryRejectsPresentedUntrustedCertificates() throws Exception {
        assertPresentedUntrustedCertificateIsRejected(MANDATORY);
    }

    private static void assertPresentedUntrustedCertificateIsRejected(
        SeBootstrap.Configuration.SSLClientAuthentication authentication) throws Exception {
        AtomicBoolean certificateWasChecked = new AtomicBoolean();
        SeBootstrap.Instance instance = startHttps(
            authentication, recordingTrustManager(certificateWasChecked, true));
        try {
            assertConnectionFails(instance, clientForcingCertificate("client.p12"));
            assertTrue("The offered certificate should have been checked", certificateWasChecked.get());
        } finally {
            stop(instance);
        }
    }

    private static SeBootstrap.Instance startHttps(
        SeBootstrap.Configuration.SSLClientAuthentication authentication,
        X509TrustManager trustManager) throws Exception {
        SeBootstrap.Configuration configuration = SeBootstrap.Configuration.builder()
            .protocol("HTTPS")
            .host("localhost")
            .port(SeBootstrap.Configuration.FREE_PORT)
            .sslContext(serverSslContext(trustManager))
            .sslClientAuthentication(authentication)
            .build();
        return SeBootstrap.start(new TestApplication(), configuration)
            .toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static SSLContext serverSslContext(X509TrustManager trustManager) throws Exception {
        char[] password = "Very5ecure".toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream inputStream = MuSeBootstrapTest.class.getResourceAsStream(
            "/io/muserver/resources/localhost.p12")) {
            keyStore.load(inputStream, password);
        }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, password);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(factory.getKeyManagers(), new X509TrustManager[]{trustManager}, null);
        return context;
    }

    private static X509TrustManager recordingTrustManager(
        AtomicBoolean certificateWasChecked, boolean reject) {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
                certificateWasChecked.set(true);
                if (reject) {
                    throw new CertificateException("Client certificate is not trusted");
                }
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

    private static URI resourceUri(SeBootstrap.Instance instance) {
        return instance.configuration().baseUriBuilder().path("application/resource").build();
    }

    private static void assertConnectionFails(SeBootstrap.Instance instance, OkHttpClient client)
        throws Exception {
        try (Response ignored = client.newCall(request(resourceUri(instance)).build()).execute()) {
            fail("Expected the TLS handshake to fail");
        } catch (IOException expected) {
            // Expected: client authentication failed during the TLS handshake.
        }
    }

    private static void stop(SeBootstrap.Instance instance) throws Exception {
        instance.stop().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @ApplicationPath("application")
    public static class TestApplication extends Application {
        @Override
        public Set<Object> getSingletons() {
            return Set.of(new TestResource());
        }
    }

    @Path("resource")
    public static class TestResource {
        @GET
        public String get() throws IOException {
            return "booted";
        }
    }
}
