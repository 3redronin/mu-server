package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.internal.Util;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

import static io.muserver.MuServerBuilder.httpsServer;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.MuAssert.stopAndCheck;

public class CORSTest {

    private MuServer server;

    @Test
    public void ifEnabledOnPreflightRequestsThenOriginIsSet() {
        @Path("/things")
        class Thing {

            @GET
            public String get() {
                return "Hello!";
            }

        }
        server = httpsServer().addHandler(
            restHandler(new Thing())
                .withCORS(CORSConfigBuilder.corsConfig()
                    .withAllowedOrigins(asList("http://example.com", "http://foo.example"))
                    .withExposedHeaders(asList("X-PINGOTHER", "Content-Type"))
                    .withMaxAge(500)
                    .withAllowCredentials(true)
                )
        ).start();
        try (okhttp3.Response resp = call(request()
            .method("OPTIONS", Util.EMPTY_REQUEST)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", " en-us,en;q=0.5")
            .header("Accept-Charset", " ISO-8859-1,utf-8;q=0.7,*;q=0.7")
            .header("Origin", "http://foo.example")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "X-PINGOTHER, content-type, X-SOMETHING_ELSE")
            .url(server.uri().resolve("/things").toString()))
        ) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Access-Control-Allow-Origin"), is("http://foo.example"));
            assertThat(resp.header("Access-Control-Allow-Methods"), is("GET, HEAD, OPTIONS"));
            assertThat(resp.header("Access-Control-Allow-Headers"), is("Content-Type, X-PINGOTHER"));
            assertThat(resp.header("Access-Control-Expose-Headers"), is(nullValue()));
            assertThat(resp.header("Access-Control-Max-Age"), is("500"));
            assertThat(resp.header("Vary"), is("origin"));
        }

    }

    @Test
    public void openAPIJSONFollowsCorsConfig() {
        @Path("/things")
        class Thing {

            @GET
            public String get() {
                return "Hello!";
            }

        }
        server = httpsServer().addHandler(
            restHandler(new Thing())
                .withCORS(CORSConfigBuilder.corsConfig()
                    .withAllowedOrigins(asList("http://example.com", "http://foo.example"))
                )
                .withOpenApiJsonUrl("/openapi.json")
        ).start();
        try (okhttp3.Response resp = call(request()
            .get()
            .header("Origin", "http://foo.example")
            .url(server.uri().resolve("/openapi.json").toString()))
        ) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Access-Control-Allow-Origin"), is("http://foo.example"));
            assertThat(resp.header("Access-Control-Allow-Methods"), is("GET"));
            assertThat(resp.header("Vary"), is("origin"));
        }



    }


    @Test
    public void ifEnabledOnSimpleRequestsThenOriginAndExposedHeadersIsSet() {
        @Path("/things")
        class Thing {

            @GET
            public String get() {
                return "Hello!";
            }

        }
        server = httpsServer().addHandler(
            restHandler(new Thing())
                .withCORS(CORSConfigBuilder.corsConfig()
                    .withAllowedOrigins(asList("http://example.com", "http://foo.example"))
                    .withExposedHeaders(asList("X-PINGOTHER", "Content-Type"))
                    .withMaxAge(500)
                    .withAllowCredentials(true)
                )
        ).start();
        try (okhttp3.Response resp = call(request()
            .get()
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", " en-us,en;q=0.5")
            .header("Accept-Charset", " ISO-8859-1,utf-8;q=0.7,*;q=0.7")
            .header("Origin", "http://foo.example")
            .url(server.uri().resolve("/things").toString()))
        ) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Access-Control-Allow-Origin"), is("http://foo.example"));
            assertThat(resp.header("Access-Control-Allow-Methods"), is("GET, HEAD, OPTIONS"));
            assertThat(resp.header("Access-Control-Allow-Headers"), is(nullValue()));
            assertThat(resp.header("Access-Control-Expose-Headers"), is("Content-Type, X-PINGOTHER"));
            assertThat(resp.header("Access-Control-Max-Age"), is(nullValue()));
            assertThat(resp.header("Vary"), is("origin"));
        }

    }


    @Test
    public void ifDisabledThenOriginIsNotSet() {
        @Path("/things")
        class Thing {

            @GET
            public String get() {
                return "Hello!";
            }

        }
        server = httpsServer().addHandler(
            restHandler(new Thing()).withCORS(CORSConfigBuilder.disabled())).start();
        try (okhttp3.Response resp = call(request()
            .method("OPTIONS", Util.EMPTY_REQUEST)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", " en-us,en;q=0.5")
            .header("Accept-Charset", " ISO-8859-1,utf-8;q=0.7,*;q=0.7")
            .header("Origin", "http://foo.example")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "X-PINGOTHER, Content-Type, X-SOMETHING_ELSE")
            .url(server.uri().resolve("/things").toString()))
        ) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Access-Control-Allow-Origin"), is("null"));
            assertThat(resp.header("Access-Control-Allow-Methods"), is(nullValue()));
            assertThat(resp.header("Access-Control-Allow-Headers"), is(nullValue()));
            assertThat(resp.header("Access-Control-Max-Age"), is(nullValue()));
            assertThat(resp.header("Vary"), is("origin"));
        }

    }

    @Test
    public void ifNonCorsRequestThenNoCorsStuffIsSent() {
        @Path("/things")
        class Thing {

            @GET
            public String get() {
                return "Hello!";
            }

        }
        server = httpsServer().addHandler(
            restHandler(new Thing()).withCORS(CORSConfigBuilder.corsConfig().withAllowedOrigins(asList("http://localhost")))).start();
        try (okhttp3.Response resp = call(request()
            .method("OPTIONS", Util.EMPTY_REQUEST)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", " en-us,en;q=0.5")
            .header("Accept-Charset", " ISO-8859-1,utf-8;q=0.7,*;q=0.7")
            .url(server.uri().resolve("/things").toString()))
        ) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Access-Control-Allow-Origin"), is(nullValue()));
            assertThat(resp.header("Access-Control-Allow-Methods"), is(nullValue()));
            assertThat(resp.header("Access-Control-Allow-Headers"), is(nullValue()));
            assertThat(resp.header("Access-Control-Max-Age"), is(nullValue()));
            assertThat(resp.header("Vary"), is("origin"));
        }

    }


    @After
    public void stop() {
        stopAndCheck(server);
    }

}
