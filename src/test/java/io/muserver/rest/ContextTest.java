package io.muserver.rest;

import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.MuServer;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.UriInfo;
import okhttp3.Response;
import org.junit.After;
import org.junit.Test;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.MuServerBuilder.muServer;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;
import static scaffolding.ServerUtils.httpsServerForTest;

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
            .withHttpsPort(0)
            .addHandler(
                context("/api/ha ha/").addHandler(restHandler(new Sample()))
            )
            .start();
        int port = server.uri().getPort();
        try (Response resp = call(request().url(server.uri().resolve("/api/ha%20ha/samples/zample/barmpit?hoo=har%20har").toString()))) {
            assertThat(resp.body().string(), equalTo("https://localhost:" + port + "/api/ha%20ha/\nsamples/zample/barmpit\n" +
                "https://localhost:" + port + "/api/ha%20ha/samples/zample/barmpit\n" +
                "https://localhost:" + port + "/api/ha%20ha/samples/zample/barmpit?hoo=har%20har\nhar har\nhar%20har\n" +
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
        this.server = httpsServerForTest()
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
        this.server = httpsServerForTest()
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

    @Test
    public void acceptableLanguagesCanBeGottenInPreferredOrder() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String get(@Context HttpHeaders headers) {
                return headers.getAcceptableLanguages().stream()
                    .map(l -> l.getLanguage() + " " + l.getCountry())
                    .collect(Collectors.joining(", "));
            }
        }
        this.server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request(server.uri().resolve("/samples"))
            .header("Accept-Language", "fr;q=0.3, es, en-US;q=0.1")
        )) {
            assertThat(resp.body().string(), equalTo("es , fr , en US"));
        }
        try (Response resp = call(request(server.uri().resolve("/samples")))) {
            assertThat(resp.body().string(), equalTo("* "));
        }
        try (Response resp = call(request(server.uri().resolve("/samples"))
            .header("Accept-Language", "fr;invalid-param=true")
        )) {
            assertThat(resp.code(), equalTo(400));
            assertThat(resp.body().string(), containsString("Invalid accept-language header"));
        }
    }

    @Test
    public void acceptableMediaTypesCanBeGotten() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String get(@Context HttpHeaders headers) {
                return headers.getAcceptableMediaTypes().stream()
                    .map(mt -> mt.getType() + "/" + mt.getSubtype() + ";" + mt.getParameters().entrySet().stream().map(p -> p.getKey() + "=" + p.getValue()).sorted().collect(Collectors.joining("; ")))
                    .collect(Collectors.joining(", "));
            }
        }
        this.server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        try (Response resp = call(request(server.uri().resolve("/samples"))
            .header("Accept", "text/html, application/vnd.mu.customer+xml;q=0.9, image/webp;level=1, */*;q=0.8;umm=hey, application/*")
        )) {
            assertThat(resp.body().string(), equalTo("image/webp;level=1, text/html;, application/*;, application/vnd.mu.customer+xml;q=0.9, */*;q=0.8; umm=hey"));
        }
        try (Response resp = call(request(server.uri().resolve("/samples")))) {
            assertThat(resp.body().string(), equalTo("*/*;"));
        }
        try (Response resp = call(request(server.uri().resolve("/samples"))
            .header("Accept", "invalid-type!!")
        )) {
            assertThat(resp.code(), equalTo(400));
            assertThat(resp.body().string(), containsString("Media types must be in the format"));
        }
    }


    @Test
    public void jaxRSRequestObjectCanBeInjected() throws Exception {
        @Path("samples")
        class Sample {
            @GET
            public String get(@Context Request jaxRequest) {
                return jaxRequest.toString();
            }
        }
        this.server = httpsServerForTest().addHandler(restHandler(new Sample())).start();
        URI url = server.uri().resolve("/samples");
        try (Response resp = call(request(url))) {
            assertThat(resp.body().string(), equalTo("GET " + url));
        }
    }

    @Test
    public void unreservedCharactersComeThroughUnencoded() throws Exception {
        @Path("~.-_")
        class Sample {
            @GET
            @Path("~.-_")
            public String get(@Context UriInfo uriInfo) {
                return uriInfo.getRequestUri().getPath();
            }
        }
        this.server = httpsServerForTest()
            .addHandler(restHandler(new Sample()))
            .start();

        try (Response resp = call(request(server.uri().resolve("/~.-_/~.-_")))) {
            assertThat(resp.body().string(), is("/~.-_/~.-_"));
            assertThat(resp.code(), is(200));
        }
        try (Response resp = call(request(server.uri().resolve("/%7E%2E%2D%5F/%7E%2E%2D%5F")))) {
            assertThat(resp.body().string(), is("/~.-_/~.-_"));
            assertThat(resp.code(), is(200));
        }
    }

    @After
    public void stop() {
        scaffolding.MuAssert.stopAndCheck(server);
    }

}
