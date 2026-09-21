package io.muserver.rest;

import com.fasterxml.jackson.databind.JsonNode;
import io.muserver.MuServer;
import io.muserver.openapi.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.GenericEntity;
import org.junit.After;
import org.junit.Test;
import scaffolding.MuAssert;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Type;
import static io.muserver.openapi.OfflineOpenApiValidator.*;
import static io.muserver.openapi.SchemaObjectBuilder.*;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.junit.Assert.*;
import static scaffolding.ClientUtils.*;
import static scaffolding.ServerUtils.httpsServerForTest;

public class OpenApi31IntegrationTest {
    private MuServer server;
    @After public void stop() { MuAssert.stopAndCheck(server); }

    @Path("/inputs") public static class Inputs {
        @GET @Produces("text/plain") public String get(@QueryParam("primitive") int primitive,
            @QueryParam("boxed") Integer boxed, @QueryParam("defaulted") @DefaultValue("7") int defaulted,
            @QueryParam("contract") @Required Integer contract, @QueryParam("values") @DefaultValue("x") List<String> values) {
            return primitive + ":" + boxed + ":" + defaulted + ":" + contract + ":" + values;
        }
    }
    @Test public void presenceDefaultsAndCollectionSettingsMatchRuntime() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Inputs()).withCollectionParameterStrategy(CollectionParameterStrategy.SPLIT_ON_COMMA)
            .withOpenApiJsonUrl("/openapi.json")).start();
        JsonNode api = document();
        JsonNode parameters = api.at("/paths/~1inputs/get/parameters");
        Map<String, JsonNode> byName = new HashMap<>(); parameters.forEach(p -> byName.put(p.get("name").asText(), p));
        for (String name : Arrays.asList("primitive", "boxed", "defaulted", "values")) assertFalse(byName.get(name).get("required").asBoolean());
        assertTrue(byName.get("contract").get("required").asBoolean());
        assertEquals(7, byName.get("defaulted").at("/schema/default").asInt());
        assertEquals("[\"x\"]", byName.get("values").at("/schema/default").toString());
        assertFalse(byName.get("values").get("explode").asBoolean());
        for (JsonNode parameter : parameters) assertFalse(parameter.get("schema").has("nullable"));
        try (okhttp3.Response response = call(request(server.uri().resolve("/inputs")))) {
            assertEquals(200, response.code()); assertEquals("0:null:7:null:[x]", response.body().string());
        }
        try (okhttp3.Response response = call(request(server.uri().resolve("/inputs?values=a,b")))) {
            assertEquals("0:null:7:null:[a, b]", response.body().string());
        }
    }

    public static class GenericBase<T> {
        @GET @Produces({"application/json", "application/problem+json"}) public CompletionStage<Map<String, List<T>>> get() { return CompletableFuture.completedFuture(Collections.emptyMap()); }
    }
    @Path("/generic") public static class GenericResource extends GenericBase<UUID> { }
    @Path("/bodies") @ApiResponse(code="400", message="Class error", response=String.class)
    public static class Bodies {
        @POST @Consumes({"application/json", "application/xml"}) @Produces("application/json")
        @ApiResponse(code="400", message="Method error", response=Integer.class)
        public GenericEntity<List<String>> post(List<String> body) { return new GenericEntity<List<String>>(body) {}; }
        @GET @Path("opaque") public Response opaque() { return Response.ok("value").build(); }
        @GET @Path("empty") @ApiResponse(code="304", message="Unchanged", response=String.class, contentType="text/plain") public String empty() { return ""; }
        @HEAD @Path("head") @ApiResponse(code="200", message="OK", response=String.class) public String head() { return ""; }
    }
    @Test public void resolvedGenericsMediaTypesAndResponseMetadataAreRetained() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new GenericResource(), new Bodies()).withOpenApiJsonUrl("/openapi.json")).start();
        JsonNode api = document();
        JsonNode content = api.at("/paths/~1generic/get/responses/200/content");
        assertEquals(2, content.size());
        JsonNode schema = content.get("application/json").get("schema");
        assertEquals("object", schema.get("type").asText());
        assertEquals("array", schema.at("/additionalProperties/type").asText());
        assertEquals("uuid", schema.at("/additionalProperties/items/format").asText());
        accepts(schema, "{\"key\":[\"93d35de9-0083-4765-8b60-822258e8ffad\"]}", true);
        accepts(schema, "{\"key\":5}", false);
        accepts(schema, "{\"key\":[null]}", false);
        JsonNode post = api.at("/paths/~1bodies/post");
        assertEquals(2, post.at("/requestBody/content").size());
        assertEquals("string", post.at("/requestBody/content/application~1json/schema/items/type").asText());
        assertEquals("Method error", post.at("/responses/400/description").asText());
        assertEquals("integer", post.at("/responses/400/content/application~1json/schema/type").asText());
        assertFalse(api.at("/paths/~1bodies~1empty/get/responses/304").has("content"));
        assertFalse(api.at("/paths/~1bodies~1head/head/responses/200").has("content"));
        assertEquals(api, document());
    }

    @Path("/registered") public static class Registered {
        @POST @Consumes("application/json") @Produces("application/json") public List<String> post(List<String> input) { return input; }
    }
    @Test public void exactGenericRegistrationAndManualRootContentSurvive() throws Exception {
        Type generic = Registered.class.getMethod("post", List.class).getGenericReturnType();
        OperationObject manual = OperationObjectBuilder.operationObject().withSummary("Manual operation").build();
        OpenAPIObjectBuilder root = OpenAPIObjectBuilder.openAPIObject().withJsonSchemaDialect("https://spec.openapis.org/oas/3.1/dialect/2024-11-10")
            .withInfo(InfoObjectBuilder.infoObject().withSummary("Root summary").build())
            .withExtension("x-owner", "test")
            .withComponents(ComponentsObjectBuilder.componentsObject().withSchemas(Collections.singletonMap("Existing", booleanSchema(true).build())).build())
            .withTags(Collections.singletonList(TagObjectBuilder.tagObject().withName("Registered").withDescription("Manual tag").build()))
            .withWebhooks(Collections.singletonMap("hook", PathItemObjectBuilder.pathItemObject().build()))
            .withPaths(PathsObjectBuilder.pathsObject().withPathItemObjects(Map.of("/manual", PathItemObjectBuilder.pathItemObject().withOperations(Collections.singletonMap("get", manual)).build(),
                "/registered", PathItemObjectBuilder.pathItemObject().withOperations(Collections.singletonMap("get", manual)).build())).build());
        server = httpsServerForTest().addHandler(restHandler(new Registered()).withOpenApiDocument(root)
            .addCustomSchema(List.class, schemaObject().withType("array").withItems(schemaObject().withType("integer").build()).build())
            .addCustomSchema(generic, "StringList", schemaObject().withType("array").withItems(schemaObject().withType("string").build()).build())
            .withOpenApiJsonUrl("/openapi.json").withOpenApiHtmlUrl("/api.html")).start();
        JsonNode api = document();
        assertEquals("#/components/schemas/StringList", api.at("/paths/~1registered/post/requestBody/content/application~1json/schema/$ref").asText());
        assertEquals("Manual operation", api.at("/paths/~1registered/get/summary").asText());
        assertEquals("Manual operation", api.at("/paths/~1manual/get/summary").asText());
        assertEquals("test", api.get("x-owner").asText());
        assertEquals("Root summary", api.at("/info/summary").asText());
        assertTrue(api.at("/components/schemas/Existing").asBoolean());
        assertTrue(api.get("webhooks").has("hook"));
        assertEquals("Manual tag", api.at("/tags/0/description").asText());
        assertEquals(api, document());
        try (okhttp3.Response response = call(request(server.uri().resolve("/api.html")))) { assertEquals(200, response.code()); assertTrue(response.body().string().contains("Manual operation")); }
    }

    @Path("/alternatives") public static class Alternatives {
        @GET @Produces("text/plain") public String get(@QueryParam("q") @Required int value) { return ""; }
        @GET @Produces("application/json") public int getOther(@QueryParam("q") String value) { return 0; }
        @POST @Consumes("application/json") public void post(@Required String body) { }
        @POST @Consumes("application/xml") public void postOther(Integer body) { }
    }
    @Test public void overloadsMergeWithoutEmptyBodiesOrLostSchemas() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Alternatives()).withOpenApiJsonUrl("/openapi.json")).start();
        JsonNode api = document();
        JsonNode get = api.at("/paths/~1alternatives/get");
        assertFalse(get.has("requestBody"));
        assertFalse(get.at("/parameters/0/required").asBoolean());
        assertEquals(2, get.at("/parameters/0/schema/anyOf").size());
        assertEquals(2, get.at("/responses/200/content").size());
        JsonNode body = api.at("/paths/~1alternatives/post/requestBody");
        assertEquals(2, body.get("content").size());
        assertFalse(body.get("required").asBoolean());
    }

    @Path("/payloads") @Produces("application/json") public static class Payloads {
        @GET @Path("entity") public GenericEntity<List<String>> entity() { return new GenericEntity<List<String>>(Collections.emptyList()) {}; }
        @GET @Path("opaque") public Response opaque() { return Response.ok().build(); }
        @GET @Path("suspended") public void suspended(@jakarta.ws.rs.container.Suspended jakarta.ws.rs.container.AsyncResponse response) { }
        @GET @Path("array") public List<String>[] array() { return null; }
        @GET @Path("unknown") public Object unknown() { return null; }
    }
    @Test public void wrappersArraysAndOpaquePayloadsDoNotInventProperties() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Payloads()).withOpenApiJsonUrl("/openapi.json")).start();
        JsonNode api = document();
        assertEquals("string", api.at("/paths/~1payloads~1entity/get/responses/200/content/application~1json/schema/items/type").asText());
        assertEquals("string", api.at("/paths/~1payloads~1array/get/responses/200/content/application~1json/schema/items/items/type").asText());
        for (String path : Arrays.asList("opaque", "suspended", "unknown")) {
            JsonNode schema = api.at("/paths/~1payloads~1" + path + "/get/responses/200/content/application~1json/schema");
            assertTrue(schema.isObject()); assertTrue(schema.isEmpty());
        }
    }

    @Test public void customizersInlineChangedRegisteredSchemas() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Registered())
            .addCustomSchema(List.class, schemaObject().withType("array").withItems(schemaObject().withType("string").build()).build())
            .addSchemaObjectCustomizer((builder, context) -> context.target() == SchemaObjectCustomizerTarget.REQUEST_BODY ? builder.withMinItems(1) : builder)
            .withOpenApiJsonUrl("/openapi.json")).start();
        JsonNode api = document();
        JsonNode input = api.at("/paths/~1registered/post/requestBody/content/application~1json/schema");
        assertFalse(input.has("$ref")); assertEquals(1, input.get("minItems").asInt());
        assertEquals("#/components/schemas/List", api.at("/paths/~1registered/post/responses/200/content/application~1json/schema/$ref").asText());
        accepts(input, "[]", false); accepts(input, "[\"a\"]", true);
    }

    @Test public void nestedRegisteredTypesUseComponentReferences() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new GenericResource())
            .addCustomSchema(UUID.class, schemaObject().withType("string").withFormat("uuid").withDescription("Registered identifier").build())
            .withOpenApiJsonUrl("/openapi.json")).start();
        JsonNode api = document();
        assertEquals("#/components/schemas/UUID", api.at("/paths/~1generic/get/responses/200/content/application~1json/schema/additionalProperties/items/$ref").asText());
        assertEquals("Registered identifier", api.at("/components/schemas/UUID/description").asText());
    }

    private JsonNode document() throws Exception {
        try (okhttp3.Response response = call(request(server.uri().resolve("/openapi.json")))) {
            assertEquals(200, response.code());
            JsonNode result = JSON.readTree(response.body().string());
            OfflineOpenApiValidator.document(result);
            return result;
        }
    }
}
