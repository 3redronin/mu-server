package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import io.muserver.SSLContextBuilder;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;

import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ContextTest {
    private MuServer server;

    @Test
    public void uriInfoCanBeGotten() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            @Path("zample/{name}")
            public String get(@Context UriInfo uri) {
                return String.join(", ",
                    uri.getBaseUri().toString(), uri.getPath(), uri.getAbsolutePath().toString(), uri.getRequestUri().toString(),
                    uri.getQueryParameters(true).getFirst("hoo"), uri.getQueryParameters(false).getFirst("hoo"),
                    uri.getMatchedResources().get(0).toString(), uri.getMatchedURIs().stream().collect(Collectors.joining(":"))
                );
            }

            @Override
            public String toString() {
                return "Sample Resource Class";
            }
        }
        startServer(new Sample());
        try (Response resp = call(request().url(server.uri().resolve("/samples/zample/barmpit?hoo=har%20har").toString()))) {
            assertThat(resp.body().string(), equalTo("https://localhost:50977/, /samples/zample/barmpit, https://localhost:50977/samples/zample/barmpit, " +
                "https://localhost:50977/samples/zample/barmpit?hoo=har%20har, har har, har%20har, " +
                "Sample Resource Class, samples/zample/barmpit:samples"));
        }
    }

    private void startServer(Object restResource) {
        this.server = MuServerBuilder.httpsServer()
            .withHttpsConnection(50977, SSLContextBuilder.unsignedLocalhostCert())
            .addHandler(RestHandlerBuilder.create(restResource)).start();
    }

    @After
    public void stop() {
        if (server != null) server.stop();
    }

}