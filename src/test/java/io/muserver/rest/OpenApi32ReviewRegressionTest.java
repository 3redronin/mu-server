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
        assertTrue(html.contains("-X GET"));
        assertTrue(html.contains("-X get"));
    }
    @Test public void additionalOperationCasingSurvivesCurlAndHeadings() throws Exception {
        String html = render(PathItemObjectBuilder.pathItemObject()
            .withAdditionalOperations(Map.of("propfind", operation("lower"), "CuStOm", operation("mixed"))).build());
        assertTrue(html.contains("-X propfind"));
        assertTrue(html.contains("-X CuStOm"));
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
    private OperationObject operation(String id) {
        return OperationObjectBuilder.operationObject().withOperationId(id).withSummary(id).build();
    }
    private String render(PathItemObject path) throws Exception {
        OpenAPIObject api = OpenAPIObjectBuilder.openAPIObject().withPaths(PathsObjectBuilder.pathsObject()
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
