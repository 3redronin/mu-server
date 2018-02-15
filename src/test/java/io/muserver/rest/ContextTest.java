package io.muserver.rest;

import io.muserver.*;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.UriInfo;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
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
                return String.join("\n",
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
            assertThat(resp.body().string(), equalTo("https://localhost:50977/\nsamples/zample/barmpit\n" +
                "https://localhost:50977/samples/zample/barmpit\n" +
                "https://localhost:50977/samples/zample/barmpit?hoo=har%20har\nhar har\nhar%20har\n" +
                "Sample Resource Class\nsamples/zample/barmpit:samples"));
        }
    }

    @Test
    public void uriInfoCanBeGottenWhenInContext() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            @Path("zample/{name}")
            public String get(@Context UriInfo uri) {
                return String.join("\n",
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
        this.server = MuServerBuilder.httpsServer()
            .withHttpsConnection(50977, SSLContextBuilder.unsignedLocalhostCert())
            .addHandler(new ContextHandler("/ha ha/", asList(RestHandlerBuilder.create(new Sample())))).start();
        try (Response resp = call(request().url(server.uri().resolve("/ha%20ha/samples/zample/barmpit?hoo=har%20har").toString()))) {
            assertThat(resp.body().string(), equalTo("https://localhost:50977/ha%20ha/\nsamples/zample/barmpit\n" +
                "https://localhost:50977/ha%20ha/samples/zample/barmpit\n" +
                "https://localhost:50977/ha%20ha/samples/zample/barmpit?hoo=har%20har\nhar har\nhar%20har\n" +
                "Sample Resource Class\nsamples/zample/barmpit:samples"));
        }
    }


    @Test
    public void muResponseCanBeGotten() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public void get(@Context MuRequest req, @Context MuResponse resp) {
                resp.sendChunk("Hello");
                resp.sendChunk(" world");
            }
        }
        startServer(new Sample());
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Hello world"));
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