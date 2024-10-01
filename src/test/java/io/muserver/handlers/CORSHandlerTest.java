package io.muserver.handlers;

import io.muserver.Method;
import io.muserver.MuServer;
import okhttp3.Response;
import okhttp3.internal.Util;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.ServerUtils;
import scaffolding.StringUtils;

import static io.muserver.handlers.CORSHandlerBuilder.corsHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class CORSHandlerTest {

    private MuServer server;

    @Test
    public void onlyVaryIsAddedForSameOrigin() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(corsHandler()
                .withCORSConfig(CORSHandlerBuilder.config().withAllOriginsAllowed())
            )
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {})
            .start();
        try (Response resp = call(request(server.uri()))) {
            assertThat(resp.headers("Vary"), contains("origin"));
            assertThat(resp.header("Access-Control-Allow-Origin"), is(nullValue()));
            assertThat(resp.header("Access-Control-Allow-Methods"), is(nullValue()));
            assertThat(resp.header("Access-Control-Max-Age"), is(nullValue()));
            assertThat(resp.header("Access-Control-Allow-Headers"), is(nullValue()));
            assertThat(resp.header("Access-Control-Expose-Headers"), is(nullValue()));
            assertThat(resp.header("Access-Control-Allow-Credentials"), is(nullValue()));
        }
    }

    @Test
    public void aHandlerCanBeCreatedFromConfig() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(CORSHandlerBuilder.config().withAllOriginsAllowed().toHandler(Method.GET, Method.HEAD))
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {})
            .start();
        try (Response resp = call(request(server.uri()).header("Origin", "http://example.org"))) {
            assertThat(resp.headers("Vary"), contains("origin"));
            assertThat(resp.header("Access-Control-Allow-Origin"), is("http://example.org"));
            assertThat(resp.header("Access-Control-Allow-Methods"), is("GET, HEAD, OPTIONS"));
        }
    }

    @Test
    public void accessHeadersAddedIfOriginIsDifferent() {
        server = ServerUtils.httpsServerForTest()
            .addHandler(corsHandler()
                .withCORSConfig(CORSHandlerBuilder.config().withAllOriginsAllowed())
            )
            .addHandler(Method.GET, "/", (req, resp, pp) -> resp.write(StringUtils.randomStringOfLength(10000))) // tripping gzip limit
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
        server = ServerUtils.httpsServerForTest()
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
            .addHandler(null, "/", (request, response, pathParams) -> {})
            .start();
        try (Response resp = call(request(server.uri()).post(Util.EMPTY_REQUEST).header("Origin", "http://example.org"))) {
            assertThat(resp.headers("Vary"), contains("origin"));
            assertThat(resp.header("Access-Control-Allow-Origin"), is("http://example.org"));
            assertThat(resp.header("Access-Control-Allow-Methods"), is("GET, HEAD, OPTIONS, POST"));
            assertThat(resp.header("Access-Control-Max-Age"), is(nullValue()));
            assertThat(resp.header("Access-Control-Allow-Headers"), is(nullValue()));
            assertThat(resp.header("Access-Control-Expose-Headers"), is("X-Exposed"));
            assertThat(resp.header("Access-Control-Allow-Credentials"), is("true"));
        }
        try (Response resp = call(request(server.uri()).method("OPTIONS", Util.EMPTY_REQUEST).header("Origin", "http://example.org"))) {
            assertThat(resp.headers("Vary"), contains("origin"));
            assertThat(resp.header("Access-Control-Allow-Origin"), is("http://example.org"));
            assertThat(resp.header("Access-Control-Allow-Methods"), is("GET, HEAD, OPTIONS, POST"));
            assertThat(resp.header("Access-Control-Max-Age"), is("600"));
            assertThat(resp.header("Access-Control-Allow-Headers"), is("X-Allowed"));
            assertThat(resp.header("Access-Control-Expose-Headers"), is("X-Exposed"));
            assertThat(resp.header("Access-Control-Allow-Credentials"), is("true"));
        }
    }

    @AfterEach
    public void destroy() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}