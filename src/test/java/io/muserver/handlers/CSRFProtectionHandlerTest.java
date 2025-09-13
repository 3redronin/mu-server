package io.muserver.handlers;

import io.muserver.Method;
import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.muserver.MuServerBuilder.muServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class CSRFProtectionHandlerTest {

    private MuServer server;

    @AfterEach
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

    private void startServer(CSRFProtectionHandler handler) {
        server = muServer()
            .withHttpPort(0)
            .addHandler(handler)
            .addHandler((req, resp) -> {
                resp.write("OK");
                return true;
            })
            .start();
    }

    @Test
    public void safeMethodsAreAlwaysAllowed() throws IOException {
        startServer(CSRFProtectionHandlerBuilder.csrfProtection().build());
        for (Method method : new Method[]{Method.GET, Method.HEAD, Method.OPTIONS}) {
            try (Response resp = call(request().method(method.name(), null).url(server.uri().toString()))) {
                assertThat(resp.code(), is(200));
                if (method == Method.GET) {
                    assertThat(resp.body().string(), is("OK"));
                }
            }
        }
    }

    @Test
    public void unsafeMethodsWithSameOriginSecFetchSiteAreAllowed() throws IOException {
        startServer(CSRFProtectionHandlerBuilder.csrfProtection().build());
        try (Response resp = call(request()
            .method("POST", okhttp3.internal.Util.EMPTY_REQUEST)
            .url(server.uri().toString())
            .header("Sec-Fetch-Site", "same-origin"))) {
            assertThat(resp.code(), is(200));
        }
    }

    @Test
    public void unsafeMethodsWithNoneSecFetchSiteAreAllowed() throws IOException {
        startServer(CSRFProtectionHandlerBuilder.csrfProtection().build());
        try (Response resp = call(request()
            .method("POST", okhttp3.internal.Util.EMPTY_REQUEST)
            .url(server.uri().toString())
            .header("Sec-Fetch-Site", "none"))) {
            assertThat(resp.code(), is(200));
        }
    }

    @Test
    public void unsafeMethodsWithMatchingOriginHeaderAreAllowed() throws IOException {
        startServer(CSRFProtectionHandlerBuilder.csrfProtection().build());
        String origin = server.uri().getScheme() + "://" + server.uri().getAuthority();
        try (Response resp = call(request()
            .method("POST", okhttp3.internal.Util.EMPTY_REQUEST)
            .url(server.uri().toString())
            .header("Origin", origin))) {
            assertThat(resp.code(), is(200));
        }
    }

    @Test
    public void unsafeMethodsWithTrustedOriginAreAllowed() throws IOException {
        String trustedOrigin = "https://trusted.com";
        startServer(CSRFProtectionHandlerBuilder.csrfProtection().addTrustedOrigin(trustedOrigin).build());
        try (Response resp = call(request()
            .method("POST", okhttp3.internal.Util.EMPTY_REQUEST)
            .url(server.uri().toString())
            .header("Origin", trustedOrigin))) {
            assertThat(resp.code(), is(200));
        }
    }

    @Test
    public void unsafeMethodsWithBypassPathAreAllowed() throws IOException {
        startServer(CSRFProtectionHandlerBuilder.csrfProtection().addBypassPath("/bypass").build());
        try (Response resp = call(request()
            .method("POST", okhttp3.internal.Util.EMPTY_REQUEST)
            .url(server.uri().resolve("/bypass").toString()))) {
            assertThat(resp.code(), is(200));
        }
    }

    @Test
    public void unsafeMethodsWithNoHeadersAreAllowed() throws IOException {
        startServer(CSRFProtectionHandlerBuilder.csrfProtection().build());
        try (Response resp = call(request()
            .method("POST", okhttp3.internal.Util.EMPTY_REQUEST)
            .url(server.uri().toString()))) {
            assertThat(resp.code(), is(200));
        }
    }

    @Test
    public void crossOriginRequestsAreRejectedByDefault() throws IOException {
        startServer(CSRFProtectionHandlerBuilder.csrfProtection().build());
        try (Response resp = call(request()
            .method("POST", okhttp3.internal.Util.EMPTY_REQUEST)
            .url(server.uri().toString())
            .header("Origin", "https://evil.com"))) {
            assertThat(resp.code(), is(400));
            assertThat(resp.body().string(), containsString("Cross-origin request rejected by CSRFHandler"));
        }
    }

    @Test
    public void customRejectionHandlerCanOverrideDefault() throws IOException {
        AtomicBoolean called = new AtomicBoolean(false);
        CSRFProtectionHandler handler = CSRFProtectionHandlerBuilder.csrfProtection()
            .withRejectionHandler((req, resp) -> {
                called.set(true);
                resp.status(403);
                resp.write("Custom rejection");
                return true;
            })
            .build();
        startServer(handler);
        try (Response resp = call(request()
            .method("POST", okhttp3.internal.Util.EMPTY_REQUEST)
            .url(server.uri().toString())
            .header("Origin", "https://evil.com"))) {
            assertThat(resp.code(), is(403));
            assertThat(resp.body().string(), containsString("Custom rejection"));
            assertThat(called.get(), is(true));
        }
    }

    @Test
    public void trustedOriginWorksWithSecFetchSiteCrossSite() throws IOException {
        String trustedOrigin = "https://trusted.com";
        startServer(CSRFProtectionHandlerBuilder.csrfProtection().addTrustedOrigin(trustedOrigin).build());
        try (Response resp = call(request()
            .method("POST", okhttp3.internal.Util.EMPTY_REQUEST)
            .url(server.uri().toString())
            .header("Sec-Fetch-Site", "cross-site")
            .header("Origin", trustedOrigin))) {
            assertThat(resp.code(), is(200));
        }
    }

    @Test
    public void bypassPathWorksWithSecFetchSiteCrossSite() throws IOException {
        startServer(CSRFProtectionHandlerBuilder.csrfProtection().addBypassPath("/bypass").build());
        try (Response resp = call(request()
            .method("POST", okhttp3.internal.Util.EMPTY_REQUEST)
            .url(server.uri().resolve("/bypass").toString())
            .header("Sec-Fetch-Site", "cross-site")
            .header("Origin", "https://evil.com"))) {
            assertThat(resp.code(), is(200));
        }
    }

    @Test
    public void originHeaderWithPortIsAllowed() throws IOException {
        startServer(CSRFProtectionHandlerBuilder.csrfProtection().build());
        String origin = server.uri().getScheme() + "://" + server.uri().getHost() + ":" + server.uri().getPort();
        try (Response resp = call(request()
            .method("POST", okhttp3.internal.Util.EMPTY_REQUEST)
            .url(server.uri().toString())
            .header("Origin", origin))) {
            assertThat(resp.code(), is(200));
        }
    }
}