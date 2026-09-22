package io.muserver.openapi;

import org.junit.Test;
import java.util.*;
import java.net.URI;
import static io.muserver.openapi.OfflineOpenApiValidator.*;
import static io.muserver.openapi.SchemaObjectBuilder.*;
import static org.junit.Assert.*;

public class OpenApi31DocumentTest {
    @Test public void completeManualDocumentValidatesWithAllReferencePositions() throws Exception {
        SchemaObject value = schemaObject().withTypes(Arrays.asList("string", "null")).withExamples(Arrays.asList("value", JsonNull.INSTANCE)).build();
        ReferenceOr<ExampleObject> example = ReferenceOr.reference("#/components/examples/Value");
        ParameterObject parameter = ParameterObjectBuilder.parameterObject().withName("q").withIn("query").withSchema(value)
            .withExamplesOrReferences(Collections.singletonMap("value", example)).build();
        HeaderObject header = HeaderObjectBuilder.headerObject().withSchema(value).withExamplesOrReferences(Collections.singletonMap("value", example)).build();
        MediaTypeObject media = MediaTypeObjectBuilder.mediaTypeObject().withSchema(schemaObject().withRef("#/components/schemas/Value").build())
            .withExamplesOrReferences(Collections.singletonMap("value", example))
            .withEncoding(Collections.singletonMap("value", EncodingObjectBuilder.encodingObject()
                .withHeadersOrReferences(Collections.singletonMap("X-Value", ReferenceOr.reference("#/components/headers/Value"))).build())).build();
        Map<String, MediaTypeObject> content = Collections.singletonMap("application/json", media);
        ResponseObject response = ResponseObjectBuilder.responseObject().withDescription("Success").withContent(content)
            .withHeadersOrReferences(Collections.singletonMap("X-Value", ReferenceOr.reference("#/components/headers/Value")))
            .withLinksOrReferences(Collections.singletonMap("next", ReferenceOr.reference("#/components/links/Next"))).build();
        OperationObject operation = OperationObjectBuilder.operationObject().withOperationId("create")
            .withParametersOrReferences(Collections.singletonList(ReferenceOr.reference("#/components/parameters/Query")))
            .withRequestBodyOrReferences(ReferenceOr.reference("#/components/requestBodies/Body"))
            .withCallbacksOrReferences(Collections.singletonMap("notification", ReferenceOr.reference("#/components/callbacks/Notification")))
            .withResponses(ResponsesObjectBuilder.responsesObject().withDefaultValueOrReferences(ReferenceOr.reference("#/components/responses/Success"))
                .withHttpStatusCodesOrReferences(Collections.singletonMap("2XX", ReferenceOr.reference("#/components/responses/Success"))).build())
            .withSecurity(Collections.emptyList()).build();
        PathItemObject path = PathItemObjectBuilder.pathItemObject().withOperations(Collections.singletonMap("post", operation))
            .withParametersOrReferences(Collections.singletonList(ReferenceOr.reference("#/components/parameters/Query"))).build();
        ComponentsObject components = ComponentsObjectBuilder.componentsObject().withSchemas(Collections.singletonMap("Value", value))
            .withExamplesOrReferences(Map.of("Value", ReferenceOr.inline(ExampleObjectBuilder.exampleObject().withValue(JsonNull.INSTANCE).build()), "Alias", example))
            .withParametersOrReferences(Map.of("Query", ReferenceOr.inline(parameter), "Alias", ReferenceOr.reference("#/components/parameters/Query")))
            .withHeadersOrReferences(Map.of("Value", ReferenceOr.inline(header), "Alias", ReferenceOr.reference("#/components/headers/Value")))
            .withResponsesOrReferences(Map.of("Success", ReferenceOr.inline(response), "Alias", ReferenceOr.reference("#/components/responses/Success")))
            .withRequestBodiesOrReferences(Map.of("Body", ReferenceOr.inline(RequestBodyObjectBuilder.requestBodyObject().withContent(content).build()), "Alias", ReferenceOr.reference("#/components/requestBodies/Body")))
            .withLinksOrReferences(Map.of("Next", ReferenceOr.inline(LinkObjectBuilder.linkObject().withOperationRef("#/paths/~1items/post").build()), "Alias", ReferenceOr.reference("#/components/links/Next")))
            .withCallbacksOrReferences(Map.of("Notification", ReferenceOr.inline(CallbackObjectBuilder.callbackObject()
                .withCallbacks(Collections.singletonMap("{$request.query.callback}", PathItemObjectBuilder.pathItemObject().build())).build()), "Alias", ReferenceOr.reference("#/components/callbacks/Notification")))
            .withSecuritySchemesOrReferences(Map.of("TLS", ReferenceOr.inline(SecuritySchemeObjectBuilder.securitySchemeObject().withType("mutualTLS").build()), "Alias", ReferenceOr.reference("#/components/securitySchemes/TLS")))
            .withPathItemsOrReferences(Map.of("Items", ReferenceOr.inline(path), "Alias", ReferenceOr.reference("#/components/pathItems/Items"))).build();
        OpenAPIObject api = OpenAPIObjectBuilder.openAPIObject().withInfo(InfoObjectBuilder.infoObject().withSummary("Example")
            .withLicense(LicenseObjectBuilder.licenseObject().withName("MIT").withIdentifier("MIT").build()).build())
            .withComponents(components).withJsonSchemaDialect("https://spec.openapis.org/oas/3.2/dialect/2026-02-26")
            .withPaths(PathsObjectBuilder.pathsObject().withPathItemObjects(Collections.singletonMap("/items", path)).build())
            .withWebhooksOrReferences(Collections.singletonMap("created", ReferenceOr.reference("#/components/pathItems/Items")))
            .withExtension("x-test", Map.of("nested", Arrays.asList(false, JsonNull.INSTANCE))).build();
        document(api);
        document(api.toBuilder().build());
        assertEquals(json(api), json(api.toBuilder().build()));
        assertEquals("3.2.1", api.openApi());
        assertTrue(json(api).at("/paths/~1items/post/security").isEmpty());
        assertThrows(IllegalStateException.class, components::responses);
        assertThrows(IllegalStateException.class, operation::requestBody);
        assertThrows(IllegalStateException.class, path::parameters);
    }

    @Test public void pathsAreOptionalAndResponsesCanBeDefaultOnly() throws Exception {
        document(OpenAPIObjectBuilder.openAPIObject().withComponents(ComponentsObjectBuilder.componentsObject().build()).build());
        document(OpenAPIObjectBuilder.openAPIObject().withWebhooks(Collections.singletonMap("hook", PathItemObjectBuilder.pathItemObject().build())).build());
        OperationObject operation = OperationObjectBuilder.operationObject().build();
        document(OpenAPIObjectBuilder.openAPIObject().withPaths(PathsObjectBuilder.pathsObject().withPathItemObjects(Collections.singletonMap("/x",
            PathItemObjectBuilder.pathItemObject().withOperations(Collections.singletonMap("get", operation)).build())).build()).build());
        assertEquals(1, json(ResponsesObjectBuilder.responsesObject().withDefaultValue(ResponseObjectBuilder.responseObject().withDescription("Any").build()).build()).size());
    }

    @Test public void contextSensitiveValidationAndDefaults() throws Exception {
        SchemaObject schema = schemaObject().build();
        for (String in : Arrays.asList("query", "cookie", "path", "header")) {
            ParameterObject base = ParameterObjectBuilder.parameterObject().withName("value").withIn(in).withRequired(in.equals("path")).withSchema(schema).build();
            String style = in.equals("query") || in.equals("cookie") ? "form" : "simple";
            boolean explode = style.equals("form");
            assertEquals(explode, base.explode());
            assertEquals(json(base), json(base.toBuilder().withStyle(style).withExplode(explode).withDeprecated(false).build()));
            if (in.equals("cookie")) assertThrows(IllegalArgumentException.class, () -> base.toBuilder().withExplode(false).build());
            else assertTrue(json(base.toBuilder().withExplode(!explode).build()).has("explode"));
        }
        assertTrue(json(EncodingObjectBuilder.encodingObject().withStyle("form").withExplode(true).withAllowReserved(false).build()).has("style"));
        assertTrue(json(EncodingObjectBuilder.encodingObject().withStyle("form").withExplode(true).withAllowReserved(false).build()).has("explode"));
        assertTrue(json(EncodingObjectBuilder.encodingObject().withStyle("form").withExplode(true).withAllowReserved(false).build()).has("allowReserved"));
        assertFalse(HeaderObjectBuilder.headerObject().withSchema(schema).build().explode());
        assertThrows(IllegalArgumentException.class, () -> ParameterObjectBuilder.parameterObject().withName("x").withIn("header").withStyle("form").withSchema(schema).build());
        assertThrows(IllegalArgumentException.class, () -> ParameterObjectBuilder.parameterObject().withName("x").withIn("query").withSchema(schema).withContent(Collections.singletonMap("text/plain", MediaTypeObjectBuilder.mediaTypeObject().build())).build());
        assertThrows(IllegalArgumentException.class, () -> LicenseObjectBuilder.licenseObject().withName("MIT").withIdentifier("MIT").withUrl(URI.create("https://example.test")).build());
        assertThrows(IllegalArgumentException.class, () -> ServerVariableObjectBuilder.serverVariableObject().withDefaultValue("x").withEnumValues(Collections.singletonList("y")).build());
        assertThrows(IllegalArgumentException.class, () -> ResponsesObjectBuilder.responsesObject().withHttpStatusCodes(Collections.singletonMap("600", ResponseObjectBuilder.responseObject().withDescription("Bad").build())).build());
        SchemaObject meaningful = schemaObject().withDefaultValue(false).withAdditionalProperties(false).withExamples(Collections.emptyList()).withReadOnly(false).build();
        assertTrue(json(meaningful).has("default")); assertTrue(json(meaningful).has("readOnly"));
        assertTrue(json(meaningful).has("additionalProperties")); assertTrue(json(meaningful).has("examples"));
    }
}
