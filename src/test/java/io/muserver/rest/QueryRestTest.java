package io.muserver.rest;

import io.muserver.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import java.lang.annotation.*;
import java.lang.reflect.Type;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import javax.xml.transform.stream.StreamSource;
import static org.junit.Assert.*;
import static scaffolding.ClientUtils.*;
import static io.muserver.rest.RestHandlerBuilder.restHandler;

@org.junit.runner.RunWith(org.junit.runners.Parameterized.class)
public class QueryRestTest {
    @org.junit.runners.Parameterized.Parameters(name = "{0}")
    public static Object[] protocols() { return new Object[]{okhttp3.Protocol.HTTP_1_1, okhttp3.Protocol.HTTP_2}; }
    private final okhttp3.Protocol protocol;
    private final okhttp3.OkHttpClient caller;
    public QueryRestTest(okhttp3.Protocol protocol) {
        this.protocol = protocol;
        this.caller = client.newBuilder().protocols(protocol == okhttp3.Protocol.HTTP_2
            ? Arrays.asList(okhttp3.Protocol.HTTP_2, okhttp3.Protocol.HTTP_1_1) : Collections.singletonList(protocol)).build();
    }
    private okhttp3.Response call(okhttp3.Request.Builder request) {
        okhttp3.Response response = scaffolding.ClientUtils.call(caller, request);
        assertEquals(protocol, response.protocol());
        return response;
    }
    private MuServerBuilder serverBuilder() {
        return MuServerBuilder.httpsServer().withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable());
    }
    private MuServer server;
    @After public void stop() { scaffolding.MuAssert.stopAndCheck(server); }
    @Target(ElementType.METHOD) @Retention(RetentionPolicy.RUNTIME) @HttpMethod("QUERY")
    public @interface CustomQuery {}

    public interface QueryContract {
        @CustomQuery @Consumes("text/plain") @Produces("text/plain")
        String inherited(String body);
    }
    @Path("/inherited") public static class Inherited implements QueryContract {
        public String inherited(String body) { return body; }
    }
    @Path("/search") public static class Search {
        @QUERY @Consumes("text/plain") @Produces("text/plain")
        public String search(String body, @QueryParam("q") String q) { return q + ":" + body; }
        @QUERY @Consumes("application/json") @Produces("application/json")
        public String json(String body) { return body; }
    }
    @Path("/root") public static class Root {
        final AtomicInteger calls = new AtomicInteger();
        @Path("/child") public Search child() { calls.incrementAndGet(); return new Search(); }
    }
    @Path("/explicit") public static class Explicit {
        @QUERY @Consumes("text/plain") public String query(String body) { return body; }
        @OPTIONS public Response options() { return Response.ok().header("Accept-Query", "\"application/custom\"").build(); }
    }
    @Path("/unrelated") public static class Unrelated {
        @GET public String get() { return "get"; }
        @POST public String post(String body) { return body; }
    }
    @Path("/conditional") public static class Conditional {
        @QUERY @Consumes("text/plain") public Response query(String body, @Context jakarta.ws.rs.core.Request request) {
            Response.ResponseBuilder precondition = request.evaluatePreconditions(new Date(1000000000000L), new EntityTag(body));
            return precondition == null ? Response.ok("result:" + body).tag(body).build() : precondition.entity("must not appear on 304").build();
        }
    }
    public static class Payload { final String value; Payload(String value) { this.value = value; } }
    @Consumes("application/custom") public static class Reader implements MessageBodyReader<Payload> {
        public boolean isReadable(Class<?> type, Type generic, Annotation[] annotations, jakarta.ws.rs.core.MediaType media) { return type == Payload.class; }
        public Payload readFrom(Class<Payload> type, Type generic, Annotation[] annotations, jakarta.ws.rs.core.MediaType media,
            jakarta.ws.rs.core.MultivaluedMap<String, String> headers, InputStream in) throws IOException {
            return new Payload(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    @Path("/provider") public static class ProviderResource {
        @QUERY @Consumes("application/custom") public String query(Payload payload) { return payload.value; }
    }
    @Path("/application") public static class ApplicationError {
        @QUERY public Response query(String body) { return Response.status(415).header("Accept-Query", "\"application/override\"").build(); }
    }

    @Path("/async-root") public static class AsyncRoot {
        final AtomicInteger calls = new AtomicInteger();
        @Path("/child") public AsyncXml child() { calls.incrementAndGet(); return new AsyncXml(); }
        @Path("/suspended") public SuspendedXml suspended() { calls.incrementAndGet(); return new SuspendedXml(); }
    }
    public static class AsyncXml {
        @QUERY @Consumes("text/plain") public String text(String body) { return body; }
        @QUERY @Consumes("application/xml") public CompletionStage<String> query(StreamSource source,
            @Context MuResponse response, @QueryParam("override") boolean override) {
            if (override) response.headers().set("Accept-Query", "\"application/custom\"");
            return read(source);
        }
        @POST @Consumes("application/xml") public CompletionStage<String> post(StreamSource source) { return read(source); }
        private static CompletionStage<String> read(StreamSource source) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    StringWriter text = new StringWriter();
                    source.getReader().transferTo(text);
                    return text.toString();
                } catch (IOException e) {
                    throw new CompletionException(e);
                }
            });
        }
    }

    public static class SuspendedXml {
        @QUERY @Consumes("text/plain") public String text(String body) { return body; }
        @QUERY @Consumes("application/xml") public void query(StreamSource source,
            @Context MuResponse response, @QueryParam("override") boolean override, @Suspended AsyncResponse suspended) {
            if (override) response.headers().set("Accept-Query", "\"application/custom\"");
            read(source, suspended);
        }
        @POST @Consumes("application/xml") public void post(StreamSource source, @Suspended AsyncResponse suspended) {
            read(source, suspended);
        }
        private void read(StreamSource source, AsyncResponse suspended) {
            AsyncXml.read(source).whenComplete((value, failure) -> {
                if (failure == null) suspended.resume(value);
                else suspended.resume(failure.getCause());
            });
        }
    }

    @Test public void suspendedRepresentationFailuresAdvertiseResolvedQueryFormats() throws Exception {
        assertAsynchronousRepresentationDiscovery("/async-root/suspended");
    }

    @Test public void asynchronousRepresentationFailuresAdvertiseResolvedQueryFormats() throws Exception {
        assertAsynchronousRepresentationDiscovery("/async-root/child");
    }

    private void assertAsynchronousRepresentationDiscovery(String resourcePath) throws Exception {
        AsyncRoot root = new AsyncRoot();
        server = serverBuilder().addHandler(restHandler(root)).start();
        for (String method : Arrays.asList("QUERY", "POST")) {
            for (boolean override : new boolean[]{false, true}) {
                String path = resourcePath + "?override=" + override;
                try (okhttp3.Response response = call(request(server.uri().resolve(path)).method(method,
                    okhttp3.RequestBody.create("<query/>".getBytes(StandardCharsets.UTF_8), okhttp3.MediaType.get("application/xml; charset=unsupported-charset"))))) {
                    assertEquals(415, response.code());
                    assertEquals(method.equals("POST") ? null : override ? "\"application/custom\"" :
                        "\"application/xml\", \"text/plain\"", response.header("Accept-Query"));
                    response.body().string();
                }
            }
        }
        assertEquals(4, root.calls.get());
        try (okhttp3.Response response = call(query(resourcePath, "application/xml; charset=UTF-8", "<query/>"))) {
            assertEquals(200, response.code());
            assertEquals("<query/>", response.body().string());
            assertNull(response.header("Accept-Query"));
        }
        assertEquals(5, root.calls.get());
    }

    private okhttp3.Request.Builder query(String path, String type, String body) {
        return request(server.uri().resolve(path)).method("QUERY", okhttp3.RequestBody.create(body, okhttp3.MediaType.get(type)));
    }

    @Test public void routingNegotiationDiscoveryAndCorsUseResolvedCandidates() throws Exception {
        Root root = new Root();
        server = serverBuilder().addHandler(ContextHandlerBuilder.context("/api").addHandler(
            restHandler(new Search(), new Inherited(), root, new Explicit(), new Unrelated())
                .withCORS(CORSConfigBuilder.corsConfig().withAllowedOrigins("https://client.test").build()))).start();
        for (String path : Arrays.asList("/api/search", "/api/root/child")) {
            try (okhttp3.Response response = call(query(path + "?q=uri", "text/plain", "body"))) {
                assertEquals(200, response.code()); assertEquals("uri:body", response.body().string());
            }
            int before = root.calls.get();
            try (okhttp3.Response response = call(request(server.uri().resolve(path)).method("OPTIONS", null)
                .header("Origin", "https://client.test").header("Access-Control-Request-Method", "QUERY"))) {
                assertEquals(200, response.code());
                assertEquals("OPTIONS, QUERY", response.header("Allow"));
                assertEquals("\"application/json\", \"text/plain\"", response.header("Accept-Query"));
                assertEquals("OPTIONS, QUERY", response.header("Access-Control-Allow-Methods"));
            }
            assertEquals(before + (path.contains("child") ? 1 : 0), root.calls.get());
            before = root.calls.get();
            try (okhttp3.Response response = call(query(path, "application/xml", "body"))) {
                assertEquals(415, response.code()); assertEquals("\"application/json\", \"text/plain\"", response.header("Accept-Query"));
            }
            assertEquals(before + (path.contains("child") ? 1 : 0), root.calls.get());
            try (okhttp3.Response response = call(query(path, "text/plain", "body").header("Accept", "image/png"))) { assertEquals(406, response.code()); }
            for (String method : Arrays.asList("GET", "HEAD")) {
                try (okhttp3.Response response = call(request(server.uri().resolve(path)).method(method, null))) {
                    assertEquals(405, response.code()); assertTrue(response.header("Allow").contains("QUERY"));
                    assertFalse(response.header("Allow").contains("GET"));
                    assertFalse(response.header("Allow").contains("HEAD"));
                }
            }
        }
        try (okhttp3.Response response = call(query("/api/inherited", "text/plain", "inherited"))) { assertEquals("inherited", response.body().string()); }
        try (okhttp3.Response response = call(request(server.uri().resolve("/api/inherited")).method("OPTIONS", null))) { assertEquals("\"text/plain\"", response.header("Accept-Query")); }
        try (okhttp3.Response response = call(query("/api/missing", "text/plain", "body"))) { assertEquals(404, response.code()); assertNull(response.header("Accept-Query")); }
        try (okhttp3.Response response = call(request(server.uri().resolve("/api/explicit")).method("OPTIONS", null))) { assertEquals("\"application/custom\"", response.header("Accept-Query")); }
        try (okhttp3.Response response = call(request(server.uri().resolve("/api/unrelated")).method("OPTIONS", null))) { assertNull(response.header("Accept-Query")); assertEquals("GET, HEAD, OPTIONS, POST", response.header("Allow")); }
        for (String method : Arrays.asList("GET", "HEAD", "POST", "UNKNOWN")) {
            try (okhttp3.Response response = call(request(server.uri().resolve("/api/unrelated")).method(method,
                method.equals("POST") ? okhttp3.RequestBody.create("post", okhttp3.MediaType.get("text/plain")) : null))) {
                assertEquals(method.equals("UNKNOWN") ? 405 : 200, response.code());
            }
        }
    }

    @Test public void conditionalQueryUsesSelectedResultValidators() throws Exception {
        server = serverBuilder().addHandler(restHandler(new Conditional())).start();
        for (String[] condition : new String[][]{
            {"If-None-Match", "\"selected\"", "304"}, {"If-None-Match", "W/\"selected\"", "304"},
            {"If-None-Match", "\"another\"", "200"}, {"If-Match", "\"another\"", "412"},
            {"If-Modified-Since", "Sun, 09 Sep 2001 01:46:40 GMT", "304"},
            {"If-Unmodified-Since", "Sat, 08 Sep 2001 01:46:40 GMT", "412"}}) {
            try (okhttp3.Response response = call(query("/conditional", "text/plain", "selected").header(condition[0], condition[1]))) {
                assertEquals(Arrays.toString(condition), Integer.parseInt(condition[2]), response.code());
                if (response.code() == 304) assertEquals("", response.body().string());
            }
        }
    }

    @Test public void providersAndFiltersWorkAndApplicationHeadersArePreserved() throws Exception {
        AtomicInteger filtered = new AtomicInteger();
        server = serverBuilder().addHandler(restHandler(new ProviderResource(), new ApplicationError(), new Search())
            .addCustomReader(new Reader())
            .addRequestFilter((ContainerRequestFilter) context -> { assertEquals("QUERY", context.getMethod()); filtered.incrementAndGet(); })
            .addResponseFilter((ContainerResponseFilter) (req, resp) -> resp.getHeaders().putSingle("X-Filtered", "yes"))).start();
        try (okhttp3.Response response = call(query("/provider", "application/custom", "provider body"))) {
            assertEquals(200, response.code()); assertEquals("provider body", response.body().string()); assertEquals("yes", response.header("X-Filtered"));
        }
        try (okhttp3.Response response = call(query("/application", "text/plain", "body"))) { assertEquals(415, response.code()); assertEquals("\"application/override\"", response.header("Accept-Query")); }
        assertEquals(2, filtered.get());
    }

    @Path("/formats") @Consumes({"text/*", "application/json;charset=UTF-8"})
    public static class Formats {
        @QUERY public String query(String body) { return body; }
    }
    @Path("/wildcard") public static class Wildcard {
        @QUERY public String query(String body) { return body; }
    }
    @Path("/suffix") public static class Suffix {
        @QUERY @Consumes({"text/plain", "application/*+json"}) public String query(String body) { return body; }
    }
    @Path("/application-exception") public static class ApplicationException {
        @QUERY public String query(String body) { throw new NotSupportedException(); }
    }
    @Test public void discoveryPreservesClassDeclarationsWildcardsAndEarlierHeaders() throws Exception {
        server = serverBuilder().addHandler((req, resp) -> {
            if (req.query().get("override") != null) resp.headers().set(HeaderNames.ACCEPT_QUERY, "\"application/earlier\"");
            return false;
        }).addHandler(restHandler(new Formats(), new Wildcard(), new Suffix(), new ApplicationException())).start();
        for (String[] expectation : new String[][]{
            {"/formats", "\"application/json\";charset=\"UTF-8\", \"text/*\""},
            {"/wildcard", "\"*/*\""}, {"/suffix", null}, {"/formats?override=true", "\"application/earlier\""}}) {
            try (okhttp3.Response response = call(request(server.uri().resolve(expectation[0])).method("OPTIONS", null))) {
                assertEquals(200, response.code()); assertEquals(expectation[1], response.header("Accept-Query"));
                assertEquals("OPTIONS, QUERY", response.header("Allow"));
            }
        }
        try (okhttp3.Response response = call(query("/formats?override=true", "image/png", "body"))) {
            assertEquals(415, response.code()); assertEquals("\"application/earlier\"", response.header("Accept-Query"));
        }
        try (okhttp3.Response response = call(query("/suffix", "image/png", "body"))) {
            assertEquals(415, response.code()); assertNull(response.header("Accept-Query"));
        }
        try (okhttp3.Response response = call(query("/application-exception", "text/plain", "body"))) {
            assertEquals(415, response.code()); assertNull(response.header("Accept-Query"));
        }
    }

    @Test public void missingProviderAdvertisesEffectiveConsumes() throws Exception {
        server = serverBuilder().addHandler(restHandler(new ProviderResource())).start();
        try (okhttp3.Response response = call(query("/provider", "application/custom", "body"))) {
            assertEquals(415, response.code()); assertEquals("\"application/custom\"", response.header("Accept-Query"));
        }
    }

    @Test public void openApiIncludesCompleteQueryOperationAndCustomizations() throws Exception {
        server = serverBuilder().addHandler(restHandler(new Inherited()).withOpenApiJsonUrl("/openapi.json")
            .withOpenApiHtmlUrl("/docs")
            .addSchemaObjectCustomizer((builder, context) -> builder.withDescription("Query schema").withExample("query example"))).start();
        try (okhttp3.Response response = call(request(server.uri().resolve("/openapi.json")))) {
            JSONObject doc = new JSONObject(response.body().string());
            io.muserver.openapi.OfflineOpenApiValidator.document(doc);
            assertEquals("3.2.1", doc.getString("openapi"));
            JSONObject operation = doc.getJSONObject("paths").getJSONObject("/inherited").getJSONObject("query");
            assertFalse(operation.getString("operationId").isEmpty());
            assertEquals("Query schema", operation.getJSONObject("requestBody").getJSONObject("content").getJSONObject("text/plain").getJSONObject("schema").getString("description"));
            assertTrue(operation.getJSONObject("responses").getJSONObject("200").getJSONObject("content").has("text/plain"));
        }
        try (okhttp3.Response response = call(request(server.uri().resolve("/docs")))) {
            String html = response.body().string();
            assertTrue(html, html.contains("QUERY")); assertTrue(html.contains("query example"));
        }
    }
}
