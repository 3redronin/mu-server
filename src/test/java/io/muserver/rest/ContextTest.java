package io.muserver.rest;

import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;
import scaffolding.ServerUtils;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.UriInfo;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.MuServerBuilder.muServer;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
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
                    uri.getMatchedResources().get(0).toString(), String.join(":", uri.getMatchedURIs())
                );
            }

            @Override
            public String toString() {
                return "Sample Resource Class";
            }
        }
        this.server = muServer()
            .withHttpsPort(0)
            .addHandler(restHandler(new Sample()))
            .start();
        int port = server.uri().getPort();
        try (Response resp = call(request().url(server.uri().resolve("/samples/zample/barmpit?hoo=har%20har").toString()))) {
            assertThat(resp.body().string(), equalTo("https://localhost:" + port + "/\nsamples/zample/barmpit\n" +
                "https://localhost:" + port + "/samples/zample/barmpit\n" +
                "https://localhost:" + port + "/samples/zample/barmpit?hoo=har%20har\nhar har\nhar%20har\n" +
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
                    uri.getMatchedResources().get(0).toString(), String.join(":", uri.getMatchedURIs())
                );
            }

            @Override
            public String toString() {
                return "Sample Resource Class";
            }
        }
        this.server = muServer()
            .withHttpsPort(50378)
            .addHandler(
                context("/api/ha ha/").addHandler(restHandler(new Sample()))
            )
            .start();
        try (Response resp = call(request().url(server.uri().resolve("/api/ha%20ha/samples/zample/barmpit?hoo=har%20har").toString()))) {
            assertThat(resp.body().string(), equalTo("https://localhost:50378/api/ha%20ha/\nsamples/zample/barmpit\n" +
                "https://localhost:50378/api/ha%20ha/samples/zample/barmpit\n" +
                "https://localhost:50378/api/ha%20ha/samples/zample/barmpit?hoo=har%20har\nhar har\nhar%20har\n" +
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
        this.server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Sample()))
            .start();
        try (Response resp = call(request().url(server.uri().resolve("/samples").toString()))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("Hello world"));
        }
    }


    @Test
    public void httpHeadersCanBeGotten() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String get(@Context HttpHeaders headers) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, List<String>> entry : headers.getRequestHeaders().entrySet().stream().sorted(Comparator.comparing(Map.Entry::getKey)).collect(Collectors.toList())) {
                    String header = entry.getKey().toLowerCase();
                    if (header.startsWith("x-")) {
                        sb.append(header).append("=").append(entry.getValue().get(0)).append(" ");
                    }
                }
                return sb.toString();
            }
        }
        this.server = ServerUtils.httpsServerForTest()
            .addHandler(restHandler(new Sample()))
            .start();
        try (Response resp = call(request()
            .url(server.uri().resolve("/samples").toString())
            .header("X-Something", "Blah")
            .header("X-Something-Else", "Another blah")
        )) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), equalTo("x-something=Blah x-something-else=Another blah "));
        }
    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}