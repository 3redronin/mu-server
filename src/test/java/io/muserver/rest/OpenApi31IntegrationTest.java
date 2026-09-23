package io.muserver.rest;

import org.json.JSONObject;
import org.json.JSONArray;
import io.muserver.MuServer;
import io.muserver.openapi.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.GenericEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scaffolding.MuAssert;
import java.util.*;
import java.util.concurrent.*;
import java.lang.reflect.Type;
import static io.muserver.openapi.OfflineOpenApiValidator.*;
import static io.muserver.openapi.SchemaObjectBuilder.*;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.*;
import static scaffolding.ServerUtils.httpsServerForTest;

public class OpenApi31IntegrationTest {
    private MuServer server;
    @AfterEach public void stop() { MuAssert.stopAndCheck(server); }

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
        JSONObject api = document();
        JSONArray parameters = (JSONArray) api.query("/paths/~1inputs/get/parameters");
        Map<String, JSONObject> byName = new HashMap<>();
        for (Object value : parameters) {
            JSONObject parameter = (JSONObject) value;
            byName.put(parameter.getString("name"), parameter);
        }
        for (String name : Arrays.asList("primitive", "boxed", "defaulted", "values")) assertFalse(byName.get(name).getBoolean("required"));
        assertTrue(byName.get("contract").getBoolean("required"));
        assertEquals(7, byName.get("defaulted").getJSONObject("schema").getInt("default"));
        assertEquals("[\"x\"]", byName.get("values").query("/schema/default").toString());
        assertFalse(byName.get("values").getBoolean("explode"));
        for (Object parameter : parameters) assertFalse(((JSONObject) parameter).getJSONObject("schema").has("nullable"));
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
        JSONObject api = document();
        JSONObject content = (JSONObject) api.query("/paths/~1generic/get/responses/200/content");
        assertEquals(2, content.length());
        JSONObject schema = content.getJSONObject("application/json").getJSONObject("schema");
        assertEquals("object", schema.getString("type"));
        assertEquals("array", schema.query("/additionalProperties/type"));
        assertEquals("uuid", schema.query("/additionalProperties/items/format"));
        accepts(schema, "{\"key\":[\"93d35de9-0083-4765-8b60-822258e8ffad\"]}", true);
        accepts(schema, "{\"key\":5}", false);
        accepts(schema, "{\"key\":[null]}", false);
        JSONObject post = (JSONObject) api.query("/paths/~1bodies/post");
        assertEquals(2, ((JSONObject) post.query("/requestBody/content")).length());
        assertEquals("string", post.query("/requestBody/content/application~1json/schema/items/type"));
        assertEquals("Method error", post.query("/responses/400/description"));
        assertEquals("integer", post.query("/responses/400/content/application~1json/schema/type"));
        assertFalse(((JSONObject) api.query("/paths/~1bodies~1empty/get/responses/304")).has("content"));
        assertFalse(((JSONObject) api.query("/paths/~1bodies~1head/head/responses/200")).has("content"));
        assertJsonEquals(api, document());
    }

    @Path("/registered") public static class Registered {
        @POST @Consumes("application/json") @Produces("application/json") public List<String> post(List<String> input) { return input; }
    }
    @Test public void exactGenericRegistrationAndManualRootContentSurvive() throws Exception {
        Type generic = Registered.class.getMethod("post", List.class).getGenericReturnType();
        OperationObject manual = OperationObjectBuilder.operationObject().withSummary("Manual operation").build();
        OpenAPIObjectBuilder root = OpenAPIObjectBuilder.openAPIObject().withJsonSchemaDialect("https://spec.openapis.org/oas/3.2/dialect/2026-02-26")
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
        JSONObject api = document();
        assertEquals("#/components/schemas/StringList", api.query("/paths/~1registered/post/requestBody/content/application~1json/schema/$ref"));
        assertEquals("Manual operation", api.query("/paths/~1registered/get/summary"));
        assertEquals("Manual operation", api.query("/paths/~1manual/get/summary"));
        assertEquals("test", api.getString("x-owner"));
        assertEquals("Root summary", api.query("/info/summary"));
        assertTrue((Boolean) api.query("/components/schemas/Existing"));
        assertTrue(api.getJSONObject("webhooks").has("hook"));
        assertEquals("Manual tag", api.query("/tags/0/description"));
        assertJsonEquals(api, document());
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
        JSONObject api = document();
        JSONObject get = (JSONObject) api.query("/paths/~1alternatives/get");
        assertFalse(get.has("requestBody"));
        assertFalse((Boolean) get.query("/parameters/0/required"));
        assertEquals(2, ((JSONArray) get.query("/parameters/0/schema/anyOf")).length());
        assertEquals(2, ((JSONObject) get.query("/responses/200/content")).length());
        JSONObject body = (JSONObject) api.query("/paths/~1alternatives/post/requestBody");
        assertEquals(2, body.getJSONObject("content").length());
        assertFalse(body.getBoolean("required"));
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
        JSONObject api = document();
        assertEquals("string", api.query("/paths/~1payloads~1entity/get/responses/200/content/application~1json/schema/items/type"));
        assertEquals("string", api.query("/paths/~1payloads~1array/get/responses/200/content/application~1json/schema/items/items/type"));
        for (String path : Arrays.asList("opaque", "suspended", "unknown")) {
            JSONObject schema = (JSONObject) api.query("/paths/~1payloads~1" + path + "/get/responses/200/content/application~1json/schema");
            assertTrue(schema.isEmpty());
        }
    }

    @Test public void customizersInlineChangedRegisteredSchemas() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Registered())
            .addCustomSchema(List.class, schemaObject().withType("array").withItems(schemaObject().withType("string").build()).build())
            .addSchemaObjectCustomizer((builder, context) -> context.target() == SchemaObjectCustomizerTarget.REQUEST_BODY ? builder.withMinItems(1) : builder)
            .withOpenApiJsonUrl("/openapi.json")).start();
        JSONObject api = document();
        JSONObject input = (JSONObject) api.query("/paths/~1registered/post/requestBody/content/application~1json/schema");
        assertFalse(input.has("$ref")); assertEquals(1, input.getInt("minItems"));
        assertEquals("#/components/schemas/List", api.query("/paths/~1registered/post/responses/200/content/application~1json/schema/$ref"));
        accepts(input, "[]", false); accepts(input, "[\"a\"]", true);
    }

    @Test public void nestedRegisteredTypesUseComponentReferences() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new GenericResource())
            .addCustomSchema(UUID.class, schemaObject().withType("string").withFormat("uuid").withDescription("Registered identifier").build())
            .withOpenApiJsonUrl("/openapi.json")).start();
        JSONObject api = document();
        assertEquals("#/components/schemas/UUID", api.query("/paths/~1generic/get/responses/200/content/application~1json/schema/additionalProperties/items/$ref"));
        assertEquals("Registered identifier", api.query("/components/schemas/UUID/description"));
    }

    @Test public void manualReferencedOperationsWinGeneratedCollisions() throws Exception {
        ReferenceOr<RequestBodyObject> body = ReferenceOr.reference("#/components/requestBodies/Input");
        ReferenceOr<ParameterObject> parameter = ReferenceOr.reference("#/components/parameters/Query");
        OperationObject manual = OperationObjectBuilder.operationObject().withSummary("Manual references")
            .withRequestBodyOrReferences(body).withParametersOrReferences(Collections.singletonList(parameter))
            .withResponses(ResponsesObjectBuilder.responsesObject().withHttpStatusCodesOrReferences(
                Collections.singletonMap("200", ReferenceOr.reference("#/components/responses/Success"))).build()).build();
        OpenAPIObjectBuilder root = OpenAPIObjectBuilder.openAPIObject()
            .withPaths(PathsObjectBuilder.pathsObject().withPathItemObjects(Collections.singletonMap("/alternatives",
                PathItemObjectBuilder.pathItemObject().withOperations(Collections.singletonMap("post", manual)).build())).build())
            .withComponents(ComponentsObjectBuilder.componentsObject()
                .withRequestBodies(Collections.singletonMap("Input", RequestBodyObjectBuilder.requestBodyObject()
                    .withContent(Collections.singletonMap("application/json", MediaTypeObjectBuilder.mediaTypeObject()
                        .withSchema(booleanSchema(true).build()).build())).build()))
                .withParameters(Collections.singletonMap("Query", ParameterObjectBuilder.parameterObject().withName("q")
                    .withIn("query").withSchema(schemaObject().withType("string").build()).build()))
                .withResponses(Collections.singletonMap("Success", ResponseObjectBuilder.responseObject().withDescription("Manual success").build())).build());
        server = httpsServerForTest().addHandler(restHandler(new Alternatives()).withOpenApiDocument(root)
            .withOpenApiJsonUrl("/openapi.json").withOpenApiHtmlUrl("/docs")).start();
        JSONObject api = document();
        assertJsonEquals(json(manual), api.query("/paths/~1alternatives/post"));
        assertTrue(api.query("/paths/~1alternatives/get") instanceof JSONObject);
        assertJsonEquals(api, document());
        try (okhttp3.Response response = call(request(server.uri().resolve("/docs")))) {
            assertEquals(200, response.code());
            assertTrue(response.body().string().contains("Manual references"));
        }
    }

    private JSONObject document() throws Exception {
        try (okhttp3.Response response = call(request(server.uri().resolve("/openapi.json")))) {
            assertEquals(200, response.code());
            JSONObject result = new JSONObject(response.body().string());
            OfflineOpenApiValidator.document(result);
            return result;
        }
    }
}
