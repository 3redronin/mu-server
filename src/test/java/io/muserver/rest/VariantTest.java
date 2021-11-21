package io.muserver.rest;

import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Variant;
import java.net.URI;
import java.util.List;
import java.util.Locale;

import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

public class VariantTest {
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

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}