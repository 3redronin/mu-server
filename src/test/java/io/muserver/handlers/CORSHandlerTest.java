package io.muserver.handlers;

import io.muserver.Method;
import io.muserver.MuServer;
import okhttp3.Response;
import okhttp3.internal.Util;
import org.junit.After;
import org.junit.Test;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.handlers.CORSHandlerBuilder.corsHandler;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class CORSHandlerTest {

    private MuServer server;

    @Test
    public void onlyVaryIsAddedForSameOrigin() {
        server = httpsServer()
            .addHandler(corsHandler()
                .withCORSConfig(CORSHandlerBuilder.config().withAllOriginsAllowed())
            )
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.headers("Vary"), contains("origin, accept-encoding"));
            assertThat(resp.header("Access-Control-Allow-Origin"), is(nullValue()));
            assertThat(resp.header("Access-Control-Allow-Methods"), is(nullValue()));
            assertThat(resp.header("Access-Control-Max-Age"), is(nullValue()));
            assertThat(resp.header("Access-Control-Allow-Headers"), is(nullValue()));
            assertThat(resp.header("Access-Control-Expose-Headers"), is(nullValue()));
            assertThat(resp.header("Access-Control-Allow-Credentials"), is(nullValue()));
        }
    }

    @Test
    public void accessHeadersAddedIfOriginIsDifferent() {
        server = httpsServer()
            .addHandler(corsHandler()
                .withCORSConfig(CORSHandlerBuilder.config().withAllOriginsAllowed())
            )
            .start();
        try (Response resp = call(request(server.uri()).header("Origin", "http://example.org"))) {
            assertThat(resp.headers("Vary"), contains("origin, accept-encoding"));
            assertThat(resp.header("Access-Control-Allow-Origin"), is("http://example.org"));
            assertThat(resp.header("Access-Control-Allow-Methods"), is("DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT"));
            assertThat(resp.header("Access-Control-Max-Age"), is(nullValue()));
            assertThat(resp.header("Access-Control-Allow-Headers"), is(nullValue()));
            assertThat(resp.header("Access-Control-Expose-Headers"), is(nullValue()));
            assertThat(resp.header("Access-Control-Allow-Credentials"), is(nullValue()));
        }
    }

    @Test
    public void optionsIsDifferentFromPost() {
        server = httpsServer()
            .addHandler(corsHandler()
                .withCORSConfig(
                    CORSHandlerBuilder.config()
                        .withAllOriginsAllowed()
                        .withExposedHeaders("X-Exposed")
                        .withAllowedHeaders("X-Allowed")
                        .withAllowCredentials(true)
                        .withMaxAge(600)
                )
                .withAllowedMethods(Method.POST, Method.GET, Method.OPTIONS)
            )
            .start();
        try (Response resp = call(request(server.uri()).post(Util.EMPTY_REQUEST).header("Origin", "http://example.org"))) {
            assertThat(resp.headers("Vary"), contains("origin, accept-encoding"));
            assertThat(resp.header("Access-Control-Allow-Origin"), is("http://example.org"));
            assertThat(resp.header("Access-Control-Allow-Methods"), is("GET, HEAD, OPTIONS, POST"));
            assertThat(resp.header("Access-Control-Max-Age"), is(nullValue()));
            assertThat(resp.header("Access-Control-Allow-Headers"), is(nullValue()));
            assertThat(resp.header("Access-Control-Expose-Headers"), is("X-Exposed"));
            assertThat(resp.header("Access-Control-Allow-Credentials"), is("true"));
        }
        try (Response resp = call(request(server.uri()).method("OPTIONS", Util.EMPTY_REQUEST).header("Origin", "http://example.org"))) {
            assertThat(resp.headers("Vary"), contains("origin, accept-encoding"));
            assertThat(resp.header("Access-Control-Allow-Origin"), is("http://example.org"));
            assertThat(resp.header("Access-Control-Allow-Methods"), is("GET, HEAD, OPTIONS, POST"));
            assertThat(resp.header("Access-Control-Max-Age"), is("600"));
            assertThat(resp.header("Access-Control-Allow-Headers"), is("X-Allowed"));
            assertThat(resp.header("Access-Control-Expose-Headers"), is("X-Exposed"));
            assertThat(resp.header("Access-Control-Allow-Credentials"), is("true"));
        }
    }

    @After
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}