package io.muserver.rest;

import io.muserver.MuServer;
import io.muserver.openapi.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.sse.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.*;
import java.util.*;
import static io.muserver.openapi.OfflineOpenApiValidator.*;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static io.muserver.rest.RestHandlerBuilder.restHandler;
import static scaffolding.ClientUtils.*;
import static scaffolding.ServerUtils.httpsServerForTest;
import static org.junit.Assert.*;

public class OpenApi32SseContractTest {
    private MuServer server;
    @After public void stop() { if (server != null) server.stop(); }
    @Path("/contracts") public static class Resource {
        @GET @Path("sink") public void sink(@Context SseEventSink sink) {}
        @GET @Path("context") public void context(@Context Sse sse) {}
        @GET @Path("mixed") @ApiSseEvent @ApiSseEvent(name="price", data=Integer.class, mediaType="application/json") public void mixed() {}
        @GET @Path("explicit") @ApiSseEvent @ApiResponse(code="200", response=String.class, contentType="text/plain", message="Owned") public void explicit() {}
        @GET @Path("bodyless") @ApiSseEvent(code="101") @ApiSseEvent(code="1XX") @ApiSseEvent(code="204") @ApiSseEvent(code="205") @ApiSseEvent(code="304") public void bodyless() {}
    }
    @Path("/duplicate") public static class Duplicate {
        @GET @ApiSseEvent(name="same") @ApiSseEvent(name="same") public void duplicate() {}
    }
    @Test public void sourcesAlternativesReferencesAndExplicitResponsesInteract() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Resource())
            .addCustomSchema(Integer.class, "Price", schemaObject().withType("integer").withMinimum(0.0).build())
            .withOpenApiJsonUrl("/openapi.json")).start();
        try (okhttp3.Response response = call(request(server.uri().resolve("/openapi.json")))) {
            JsonNode api = JSON.readTree(response.body().string()); document(api);
            for (String source : Arrays.asList("sink", "context")) {
                JsonNode item = api.at("/paths/~1contracts~1" + source + "/get/responses/200/content/text~1event-stream/itemSchema");
                accepts(item, "{\"data\":\"hello\"}", true);
                accepts(item, "{\"data\":false}", false);
                accepts(item, "{\"data\":\"hello\",\"retry\":-1}", false);
            }
            JsonNode mixed = api.at("/paths/~1contracts~1mixed/get/responses/200/content/text~1event-stream/itemSchema");
            assertEquals(2, mixed.get("anyOf").size());
            assertFalse(mixed.has("oneOf"));
            assertEquals("#/components/schemas/Price", mixed.at("/anyOf/1/properties/data/contentSchema/$ref").asText());
            JsonNode explicit = api.at("/paths/~1contracts~1explicit/get/responses/200");
            assertEquals("Owned", explicit.get("description").asText());
            assertFalse(explicit.get("content").has("text/event-stream"));
            for (JsonNode bodyless : api.at("/paths/~1contracts~1bodyless/get/responses")) assertFalse(bodyless.has("content"));
        }
    }
    @Test public void duplicatesFailBeforeDocumentPublication() throws Exception {
        server = httpsServerForTest().addHandler(restHandler(new Duplicate()).withOpenApiJsonUrl("/openapi.json")).start();
        try (okhttp3.Response response = call(request(server.uri().resolve("/openapi.json")))) {
            assertEquals(500, response.code());
        }
    }
    @Test public void manualOperationsOwnGeneratedSseContracts() throws Exception {
        OperationObject manual = OperationObjectBuilder.operationObject().withOperationId("manual")
            .withResponses(ResponsesObjectBuilder.responsesObject().withHttpStatusCodes(Map.of("202", ResponseObjectBuilder.responseObject().withSummary("Manual").build())).build()).build();
        OpenAPIObjectBuilder api = OpenAPIObjectBuilder.openAPIObject().withPaths(PathsObjectBuilder.pathsObject()
            .withPathItemObjects(Map.of("/contracts/mixed", PathItemObjectBuilder.pathItemObject().withOperations(Map.of("get", manual)).build())).build());
        server = httpsServerForTest().addHandler(restHandler(new Resource()).withOpenApiDocument(api).withOpenApiJsonUrl("/openapi.json")).start();
        try (okhttp3.Response response = call(request(server.uri().resolve("/openapi.json")))) {
            JsonNode generated = JSON.readTree(response.body().string()); document(generated);
            assertEquals(json(manual), generated.at("/paths/~1contracts~1mixed/get"));
        }
    }
}
