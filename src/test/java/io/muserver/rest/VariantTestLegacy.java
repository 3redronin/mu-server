package io.muserver.rest;

import io.muserver.ContentTypes;
import io.muserver.HeaderNames;
import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Variant;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class VariantTestLegacy {
    private MuServer server;

    @Test
    public void jaxRSRequestObjectCanBeInjected() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public javax.ws.rs.core.Response get(@Context Request jaxRequest) {
                List<Variant> variants = Variant.VariantListBuilder.newInstance()
                    .mediaTypes(MediaType.APPLICATION_XML_TYPE, MediaType.APPLICATION_JSON_TYPE)
                    .languages(new Locale("en"), new Locale("es"))
                    .encodings("identity", "gzip").build();
                Variant v = jaxRequest.selectVariant(variants);
                javax.ws.rs.core.Response.ResponseBuilder builder = javax.ws.rs.core.Response.ok("{}");
                builder
                    .type(v.getMediaType())
                    .language(v.getLanguage())
                    .header("Content-Encoding", v.getEncoding());
                return builder.build();
            }
        }
        this.server = httpsServerForTest()
            .withGzipEnabled(false)
            .addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url)
        .header("accept-language", "en;q=0.8, es")
        .header("accept-encoding", "gzip;q=0.9, identity")
        .header("accept", "text/plain;charset=utf-8,text/*,application/json")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("content-type"), equalTo("application/json"));
            assertThat(resp.header("content-encoding"), equalTo("identity"));
            assertThat(resp.header("content-language"), equalTo("es"));
            assertThat(resp.headers("vary"), hasItems("accept-language", "accept", "accept-encoding"));
            assertThat(resp.body().string(), equalTo("{}"));
        }

        try (Response resp = call(request(url)
            .header("accept-encoding", "gzip;q=0.9, identity")
            .header("accept", "text/plain;charset=utf-8,text/*,application/json")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("content-type"), equalTo("application/json"));
            assertThat(resp.header("content-encoding"), equalTo("identity"));
            assertThat(resp.header("content-language"), oneOf("es", "en"));
            assertThat(resp.headers("vary"), hasItems("accept-language", "accept", "accept-encoding"));
            assertThat(resp.body().string(), equalTo("{}"));
        }

    }

    @Test
    public void htmlPreferredOverText() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String get(@Context Request jaxRequest) {
                List<Variant> variants = Variant.VariantListBuilder.newInstance()
                    .mediaTypes(MediaType.TEXT_HTML_TYPE, MediaType.TEXT_PLAIN_TYPE)
                    .build();
                Variant v = jaxRequest.selectVariant(variants);
                return "Preferred type is " + v.getMediaType();
            }
        }
        this.server = httpsServerForTest()
            .addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url)
            .header("accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Preferred type is text/html"));
        }
    }

    @Test
    public void serverSidePreferenceCanBeUsedToTieBreak() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String get(@Context Request jaxRequest) {
                List<Variant> variants = Variant.VariantListBuilder.newInstance()
                    .mediaTypes(new MediaType("text", "html", Collections.singletonMap("qs", "0.5")), new MediaType("text", "plain", Collections.singletonMap("qs", "1.0")))
                    .build();
                Variant v = jaxRequest.selectVariant(variants);
                return "Preferred type is " + v.getMediaType();
            }
        }
        this.server = httpsServerForTest()
            .addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url)
            .header("accept", "text/html,text/plain")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Preferred type is text/plain;qs=1.0"));
        }
        try (Response resp = call(request(url)
            .header("accept", "text/html,text/plain;q=0.8")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.body().string(), equalTo("Preferred type is text/html;qs=0.5"));
        }
    }


    @Test
    public void variantsCanBeSetOnResponseBuilder() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            @Produces("text/plain")
            public javax.ws.rs.core.Response get() {
                return javax.ws.rs.core.Response.ok("Blah")
                    .variants(
                        new Variant(MediaType.APPLICATION_JSON_TYPE, "en", "gzip"),
                        null,
                        new Variant(MediaType.APPLICATION_JSON_TYPE, null, null, null),
                        new Variant(MediaType.TEXT_HTML_TYPE, "en", "US", null)
                    )
                    .build();
            }

            @GET
            @Produces("text/plain")
            @Path("encoding")
            public javax.ws.rs.core.Response encodingOnly() {
                return javax.ws.rs.core.Response.ok("Blah")
                    .variants(
                        new Variant(null, null, null, "pkunzip")
                    )
                    .build();
            }
        }
        this.server = httpsServerForTest()
            .withGzipEnabled(false)
            .addHandler((request, response) -> {
                response.headers().add(HeaderNames.VARY, "x-something");
                return false;
            })
            .addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request(server.uri().resolve("/samples")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("vary").toString(), resp.headers("vary"), containsInAnyOrder("origin", "content-type", "content-language", "x-something"));
        }
        try (Response resp = call(request(server.uri().resolve("/samples/encoding")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers("vary").toString(), resp.headers("vary"), containsInAnyOrder("origin", "content-encoding", "x-something"));
        }
    }


    @Test
    public void variantCanBeSetOnResponseBuilder() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            @Produces("text/plain")
            public javax.ws.rs.core.Response get() {
                return javax.ws.rs.core.Response.ok("{}")
                    .variant(new Variant(MediaType.APPLICATION_JSON_TYPE, "en", "NZ", "bgzip"))
                    .build();
            }

            @GET
            @Produces("text/plain")
            @Path("nulls")
            public javax.ws.rs.core.Response getNulls() {
                return javax.ws.rs.core.Response.ok("{}")
                    .variant(new Variant(MediaType.APPLICATION_JSON_TYPE, "en", "NZ", "bgzip"))
                    .variant(new Variant(MediaType.APPLICATION_XML_TYPE, null, null, null))
                    .build();
            }

            @GET
            @Produces("text/plain")
            @Path("null")
            public javax.ws.rs.core.Response getNull() {
                return javax.ws.rs.core.Response.ok("{}")
                    .variant(new Variant(MediaType.APPLICATION_JSON_TYPE, "en", "NZ", "bgzip"))
                    .variant(null)
                    .build();
            }
        }
        this.server = httpsServerForTest()
            .withGzipEnabled(false)
            .addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request(server.uri().resolve("/samples")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers(HeaderNames.CONTENT_TYPE.toString()), contains("application/json"));
            assertThat(resp.headers(HeaderNames.CONTENT_LANGUAGE.toString()), contains("en-NZ"));
            assertThat(resp.headers(HeaderNames.CONTENT_ENCODING.toString()), contains("bgzip"));
        }
        try (Response resp = call(request(server.uri().resolve("/samples/nulls")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers(HeaderNames.CONTENT_TYPE.toString()), contains("application/xml"));
            assertThat(resp.headers(HeaderNames.CONTENT_LANGUAGE.toString()), empty());
            assertThat(resp.headers(HeaderNames.CONTENT_ENCODING.toString()), empty());
        }
        try (Response resp = call(request(server.uri().resolve("/samples/null")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.headers(HeaderNames.CONTENT_TYPE.toString()), contains(ContentTypes.TEXT_PLAIN_UTF8.toString()));
            assertThat(resp.headers(HeaderNames.CONTENT_LANGUAGE.toString()), empty());
            assertThat(resp.headers(HeaderNames.CONTENT_ENCODING.toString()), empty());
        }
    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}
