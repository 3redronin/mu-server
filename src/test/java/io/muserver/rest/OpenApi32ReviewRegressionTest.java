package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.openapi.*;
import jakarta.ws.rs.*;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import java.io.*;
import java.net.URI;
import java.util.*;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.junit.Assert.*;
import static scaffolding.ClientUtils.*;
import static scaffolding.ServerUtils.httpsServerForTest;

public class OpenApi32ReviewRegressionTest {
    private MuServer server;
    @After public void stop() { if (server != null) server.stop(); }
    @Path("/generated") public static class Resource {
        @GET @Produces("text/event-stream") public String events() { return "data: hello\n\n"; }
    }
    @Path("/parameterized") public static class ParameterizedResource {
        @GET @Produces({"text/event-stream;charset=UTF-8", "text/event-stream;charset=US-ASCII"})
        public String events() { return "data: hello\n\n"; }
    }
    @Test public void parameterizedSseRepresentationsEachKeepTheirItemContract() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new ParameterizedResource()).withOpenApiJsonUrl("/openapi.json")).start();
        JSONObject content = document().getJSONObject("paths").getJSONObject("/parameterized").getJSONObject("get")
            .getJSONObject("responses").getJSONObject("200").getJSONObject("content");
        assertEquals(2, content.length());
        for (String key : content.keySet()) {
            assertTrue(key.contains("charset="));
            assertTrue(content.getJSONObject(key).has("schema"));
            assertTrue(content.getJSONObject(key).has("itemSchema"));
        }
    }
    @Test public void missingResponseDescriptionsAreBackfilledWithoutReplacingExplicitEmptyValues() {
        ResponseObject primary = ResponseObjectBuilder.responseObject().withSummary("Primary summary").build();
        ResponseObject secondary = ResponseObjectBuilder.responseObject().withDescription("Fallback description").withSummary("Secondary summary").build();
        ResponseObject merged = ResponseObjectBuilder.mergeResponses(primary, secondary).build();
        assertEquals("Fallback description", merged.description());
        assertEquals("Primary summary", merged.summary());
        assertNull(primary.description());
        assertEquals("", ResponseObjectBuilder.mergeResponses(primary.toBuilder().withDescription("").build(), secondary).build().description());
        assertEquals("Explicit", ResponseObjectBuilder.mergeResponses(primary.toBuilder().withDescription("Explicit").build(), secondary).build().description());
    }
    @Test public void wholeStreamSchemaAndExampleSurviveItemInference() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Resource()).withOpenApiJsonUrl("/openapi.json")
            .addSchemaObjectCustomizer((builder, context) -> context.target() == SchemaObjectCustomizerTarget.RESPONSE_BODY
                ? builder.withType("string").withDescription("Complete serialized stream").withExample("data: hello\n\n") : builder)).start();
        JSONObject media = document().getJSONObject("paths").getJSONObject("/generated").getJSONObject("get")
            .getJSONObject("responses").getJSONObject("200").getJSONObject("content").getJSONObject("text/event-stream");
        assertEquals("Complete serialized stream", media.getJSONObject("schema").getString("description"));
        assertEquals("data: hello\n\n", media.getString("example"));
        assertTrue(media.has("itemSchema"));
    }
    @Test public void standardAndLowercaseAdditionalOperationsBothRender() throws Exception {
        String html = render(PathItemObjectBuilder.pathItemObject().withOperations(Map.of("get", operation("standard")))
            .withAdditionalOperations(Map.of("get", operation("lowercase"))).build());
        assertTrue(html.contains("id=\"standard\""));
        assertTrue(html.contains("id=\"lowercase\""));
        assertTrue(html.contains("-X &#x27;GET&#x27;"));
        assertTrue(html.contains("-X &#x27;get&#x27;"));
    }
    @Test public void additionalOperationCasingSurvivesCurlAndHeadings() throws Exception {
        String html = render(PathItemObjectBuilder.pathItemObject()
            .withAdditionalOperations(Map.of("propfind", operation("lower"), "CuStOm", operation("mixed"))).build());
        assertTrue(html.contains("-X &#x27;propfind&#x27;"));
        assertTrue(html.contains("-X &#x27;CuStOm&#x27;"));
        assertFalse(html.contains("PROPFIND"));
        assertFalse(html.contains("CUSTOM"));
    }
    @Test public void generatedIdsAvoidAdditionalOperationIds() throws Exception {
        OpenAPIObjectBuilder api = OpenAPIObjectBuilder.openAPIObject().withPaths(PathsObjectBuilder.pathsObject()
            .withPathItemObjects(Map.of("/manual", PathItemObjectBuilder.pathItemObject()
                .withAdditionalOperations(Map.of("propfind", operation("GET_generated"))).build())).build());
        server = httpsServerForTest().addHandler(restHandler(new Resource()).withOpenApiDocument(api).withOpenApiJsonUrl("/openapi.json")).start();
        JSONObject paths = document().getJSONObject("paths");
        assertEquals("GET_generated_2", paths.getJSONObject("/generated").getJSONObject("get").getString("operationId"));
        assertEquals("GET_generated", paths.getJSONObject("/manual").getJSONObject("additionalOperations").getJSONObject("propfind").getString("operationId"));
    }
    @Path("/interface-events")
    @ApiSseEvent(name = "price", data = Integer.class, mediaType = "application/json")
    public interface EventContract { @GET void events(); }
    @Path("/interface-events")
    public interface ExtendedEventContract extends EventContract {}
    public static class InterfaceEvents implements ExtendedEventContract { public void events() {} }
    @ApiSseEvent(name = "price", data = Long.class, mediaType = "application/json")
    public static class OverriddenInterfaceEvents implements ExtendedEventContract { public void events() {} }
    @Test public void interfaceSseDefaultsAreInheritedThroughNestedInterfaces() throws Exception {
        assertInterfaceEvent(new InterfaceEvents(), "int32");
    }
    @Test public void concreteSseDeclarationsOverrideInterfaceDefaults() throws Exception {
        assertInterfaceEvent(new OverriddenInterfaceEvents(), "int64");
    }
    private void assertInterfaceEvent(Object resource, String format) throws Exception {
        server = httpsServerForTest().addHandler(restHandler(resource).withOpenApiJsonUrl("/openapi.json")).start();
        JSONObject responses = document().getJSONObject("paths").getJSONObject("/interface-events")
            .getJSONObject("get").getJSONObject("responses");
        assertFalse(responses.has("204"));
        JSONObject properties = responses.getJSONObject("200").getJSONObject("content").getJSONObject("text/event-stream")
            .getJSONObject("itemSchema").getJSONObject("properties");
        assertEquals("price", properties.getJSONObject("event").getString("const"));
        assertEquals(format, properties.getJSONObject("data").getJSONObject("contentSchema").getString("format"));
    }
    @Test public void querystringMediaExamplesAreRenderedAsRawQueries() throws Exception {
        assertRawQuery(MediaTypeObjectBuilder.mediaTypeObject().withExample("filter=active&sort=date%20desc").build());
    }
    @Test public void querystringSerializedExamplesTakePrecedenceOverParsedData() throws Exception {
        assertRawQuery(MediaTypeObjectBuilder.mediaTypeObject().withExamples(Map.of("filter", ExampleObjectBuilder.exampleObject()
            .withDataValue(Map.of("filter", "active")).withSerializedValue("filter=active&sort=date%20desc").build())).build());
    }
    @Test public void querystringStringSchemaExamplesAreRenderedAsRawQueries() throws Exception {
        assertRawQuery(MediaTypeObjectBuilder.mediaTypeObject().withSchema(schemaObject().withType("string")
            .withExample("filter=active&sort=date%20desc").build()).build());
    }
    @Test public void querystringResolvesReusableMediaAndExampleReferences() throws Exception {
        ParameterObject parameter = ParameterObjectBuilder.parameterObject().withName("rawQuery").withIn("querystring")
            .withContentOrReferences(Map.of("application/x-www-form-urlencoded", ReferenceOr.reference("#/components/mediaTypes/Query"))).build();
        ComponentsObject components = ComponentsObjectBuilder.componentsObject().withMediaTypes(Map.of("Query",
            MediaTypeObjectBuilder.mediaTypeObject().withExamplesOrReferences(Map.of("sample", ReferenceOr.reference("#/components/examples/Query"))).build()))
            .withExamples(Map.of("Query", ExampleObjectBuilder.exampleObject().withSerializedValue("filter=active&sort=date%20desc").build())).build();
        String html = render(PathItemObjectBuilder.pathItemObject().withOperations(Map.of("get", operation("query")
            .toBuilder().withParameters(List.of(parameter)).build())).build(), components);
        assertTrue(html, html.contains("?filter=active&amp;sort=date%20desc"));
        assertFalse(html.contains("rawQuery="));
    }
    @Test public void curlPreservesApostrophesInRawQueryExamples() throws Exception {
        String query = "author=O'Reilly&sort=date%20desc";
        ParameterObject parameter = ParameterObjectBuilder.parameterObject().withName("search").withIn("querystring")
            .withContent(Map.of("text/plain", MediaTypeObjectBuilder.mediaTypeObject().withExample(query).build())).build();
        String html = render(PathItemObjectBuilder.pathItemObject().withOperations(Map.of("get", operation("query")
            .toBuilder().withParameters(List.of(parameter)).build())).build());
        assertCurlArguments(html, "GET", "https://example.test/example?" + query);
    }
    @Test public void curlPreservesShellMetacharactersInCustomMethods() throws Exception {
        for (String method : List.of("FOO'BAR", "FOO&BAR", "FOO|BAR", "FOO`BAR", "FOO$BAR", "foo!#%*+-.^_~bar")) {
            String html = render(PathItemObjectBuilder.pathItemObject()
                .withAdditionalOperations(Map.of(method, operation("custom"))).build());
            assertCurlArguments(html, method, "https://example.test/example");
        }
    }
    @Test public void querystringParameterExampleOverridesMediaExampleIncludingEmptyValue() throws Exception {
        for (String example : List.of("author=O'Reilly&sort=date%20desc", "")) {
            assertParameterQueryExample(queryParameter().withExample(example).build(), null, example);
        }
    }
    @Test public void querystringParameterExamplesOverrideMediaExamples() throws Exception {
        for (ExampleObject example : List.of(
            ExampleObjectBuilder.exampleObject().withValue("filter=active").build(),
            ExampleObjectBuilder.exampleObject().withDataValue(Map.of("filter", "active"))
                .withSerializedValue("filter=active").build())) {
            assertParameterQueryExample(queryParameter().withExamples(Map.of("sample", example)).build(), null, "filter=active");
        }
    }
    @Test public void querystringParameterExampleReferencesAreResolved() throws Exception {
        ComponentsObject components = ComponentsObjectBuilder.componentsObject().withExamples(Map.of("Query",
            ExampleObjectBuilder.exampleObject().withSerializedValue("filter=active").build())).build();
        assertParameterQueryExample(queryParameter().withExamplesOrReferences(Map.of("sample",
            ReferenceOr.reference("#/components/examples/Query"))).build(), components, "filter=active");
    }
    private ParameterObjectBuilder queryParameter() {
        return ParameterObjectBuilder.parameterObject().withName("search").withIn("querystring")
            .withContent(Map.of("text/plain", MediaTypeObjectBuilder.mediaTypeObject().withExample("wrong=fallback").build()));
    }
    private void assertParameterQueryExample(ParameterObject parameter, ComponentsObject components, String query) throws Exception {
        String html = render(PathItemObjectBuilder.pathItemObject().withOperations(Map.of("get", operation("query")
            .toBuilder().withParameters(List.of(parameter)).build())).build(), components);
        assertCurlArguments(html, "GET", "https://example.test/example" + (query.isEmpty() ? "" : "?" + query));
    }
    private void assertCurlArguments(String html, String method, String url) throws Exception {
        org.junit.Assume.assumeTrue(new File("/bin/sh").canExecute());
        int start = html.indexOf("<code>curl ") + "<code>".length();
        String command = html.substring(start, html.indexOf("</code>", start))
            .replace("&#x27;", "'").replace("&#x2F;", "/").replace("&quot;", "\"")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        // A local stub records arguments; no HTTP request or external curl process is run.
        Process process = new ProcessBuilder("/bin/sh", "-c", "curl() { printf '%s\\n' \"$@\"; }; " + command)
            .redirectErrorStream(true).start();
        try {
            assertTrue("Shell did not finish", process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(output, 0, process.exitValue());
            assertEquals(List.of("-is", "-X", method, url), Arrays.asList(output.stripTrailing().split("\\n")));
        } finally {
            process.destroyForcibly();
        }
    }
    private void assertRawQuery(MediaTypeObject media) throws Exception {
        ParameterObject parameter = ParameterObjectBuilder.parameterObject().withName("rawQuery").withIn("querystring")
            .withContent(Map.of("application/x-www-form-urlencoded", media)).build();
        String html = render(PathItemObjectBuilder.pathItemObject().withOperations(Map.of("get", operation("query")
            .toBuilder().withParameters(List.of(parameter)).build())).build());
        assertTrue(html, html.contains("https:&#x2F;&#x2F;example.test&#x2F;example?filter=active&amp;sort=date%20desc"));
        assertFalse(html.contains("rawQuery="));
    }
    private OperationObject operation(String id) {
        return OperationObjectBuilder.operationObject().withOperationId(id).withSummary(id).build();
    }
    private String render(PathItemObject path) throws Exception {
        return render(path, null);
    }
    private String render(PathItemObject path, ComponentsObject components) throws Exception {
        OpenAPIObject api = OpenAPIObjectBuilder.openAPIObject().withComponents(components).withPaths(PathsObjectBuilder.pathsObject()
            .withPathItemObjects(Map.of("/example", path)).build()).build();
        StringWriter output = new StringWriter();
        try (BufferedWriter writer = new BufferedWriter(output)) { new HtmlDocumentor(writer, api, "", URI.create("https://example.test")).writeHtml(); }
        return output.toString();
    }
    private JSONObject document() throws Exception {
        try (okhttp3.Response response = call(request(server.uri().resolve("/openapi.json")))) {
            assertEquals(200, response.code()); return new JSONObject(response.body().string());
        }
    }
}
