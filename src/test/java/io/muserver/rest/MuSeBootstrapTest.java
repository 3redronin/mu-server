package io.muserver.rest;

import io.muserver.MuServer;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import okhttp3.Response;
import org.junit.Test;
import org.junit.BeforeClass;

import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertNull;
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
