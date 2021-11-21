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
import static org.hamcrest.Matchers.equalTo;
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
                Variant.VariantListBuilder vb = Variant.VariantListBuilder.newInstance();
                vb.mediaTypes(MediaType.APPLICATION_XML_TYPE,
                    MediaType.APPLICATION_JSON_TYPE)
                    .languages(new Locale("en"), new Locale("es"))
                    .encodings("deflate", "gzip").add();
                List<Variant> variants = vb.build();
                Variant v = jaxRequest.selectVariant(variants);
                javax.ws.rs.core.Response.ResponseBuilder builder = javax.ws.rs.core.Response.ok("{}");
                builder
                    .type(v.getMediaType())
                    .language(v.getLanguage())
                    .header("Content-Encoding", v.getEncoding());
                return builder.build();
            }
        }
        this.server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url)
        .header("accept-language", "en;q=0.8, es")
        .header("accept", "text/plain;charset=utf-8,text/*,application/json")
        )) {
            assertThat(resp.code(), equalTo(200));
            assertThat(resp.header("content-type"), equalTo("ct"));
            assertThat(resp.header("content-encoding"), equalTo("ct"));
            assertThat(resp.header("content-language"), equalTo("es"));
            assertThat(resp.header("vary"), equalTo("something"));
            assertThat(resp.body().string(), equalTo("{}"));
        }
    }


    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}