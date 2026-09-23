package io.muserver.rest;

import io.muserver.openapi.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.net.URI;
import java.util.*;
import static io.muserver.openapi.SchemaObjectBuilder.*;
import static org.junit.jupiter.api.Assertions.*;

public class OpenApi31HtmlTest {
    @Test public void localCyclicAndExternalReferencesAndBooleanUnionsRender() throws Exception {
        ComponentsObject components = ComponentsObjectBuilder.componentsObject()
            .withSchemas(Map.of("Union", schemaObject().withTypes(Arrays.asList("string", "null")).withExamples(Collections.singletonList("sample")).build(),
                "Cycle", schemaObject().withRef("#/components/schemas/Cycle").build()))
            .withParameters(Collections.singletonMap("q", ParameterObjectBuilder.parameterObject().withName("q").withIn("query").withSchema(schemaObject().withRef("#/components/schemas/Union").build()).build()))
            .withRequestBodies(Collections.singletonMap("Body", RequestBodyObjectBuilder.requestBodyObject().withContent(Map.of(
                "application/json", MediaTypeObjectBuilder.mediaTypeObject().withSchema(booleanSchema(true).build()).build(),
                "application/xml", MediaTypeObjectBuilder.mediaTypeObject().withSchema(schemaObject().withRef("#/components/schemas/Cycle").build()).build())).build()))
            .withResponses(Collections.singletonMap("OK", ResponseObjectBuilder.responseObject().withDescription("Resolved response").build())).build();
        OperationObject operation = OperationObjectBuilder.operationObject().withParametersOrReferences(Arrays.asList(
            ReferenceOr.reference("#/components/parameters/q"), ReferenceOr.reference("https://example.test/parameters.json#/q")))
            .withRequestBodyOrReferences(ReferenceOr.reference("#/components/requestBodies/Body"))
            .withResponses(ResponsesObjectBuilder.responsesObject().withDefaultValueOrReferences(ReferenceOr.reference("#/components/responses/OK")).build()).build();
        OpenAPIObject api = OpenAPIObjectBuilder.openAPIObject().withComponents(components)
            .withPaths(PathsObjectBuilder.pathsObject().withPathItemObjects(Map.of(
                "/item", PathItemObjectBuilder.pathItemObject().withOperations(Collections.singletonMap("post", operation)).build(),
                "/external", PathItemObjectBuilder.pathItemObject().withRef("https://example.test/path.json").build(),
                "/cycle", PathItemObjectBuilder.pathItemObject().withRef("#/paths/~1cycle").build())).build()).build();
        String html = render(api);
        assertTrue(html.contains("string | null"));
        assertTrue(html.contains("any value"));
        assertTrue(html.contains(io.muserver.Mutils.htmlEncode("https://example.test/parameters.json#/q")));
        assertTrue(html.contains(io.muserver.Mutils.htmlEncode("https://example.test/path.json")));
        assertTrue(html.contains(io.muserver.Mutils.htmlEncode("#/components/schemas/Cycle")));
        assertTrue(html.contains("Resolved response"));
        assertTrue(html.contains("default"));
    }

    @Test public void curlUsesJsonForObjectsAndOneContentAlternative() throws Exception {
        SchemaObject schema = schemaObject().withType("object").withProperties(Collections.singletonMap("name", schemaObject()
            .withType("string").withExamples(Collections.singletonList("Alice")).build())).build();
        RequestBodyObject body = RequestBodyObjectBuilder.requestBodyObject().withContent(Map.of(
            "application/json", MediaTypeObjectBuilder.mediaTypeObject().withSchema(schema).build(),
            "application/xml", MediaTypeObjectBuilder.mediaTypeObject().withExample("<name>Alice</name>").build())).build();
        String html = render(api(body));
        assertTrue(html.contains("--data-binary"));
        assertTrue(html.contains("{&quot;name&quot;:&quot;Alice&quot;}"));
        assertFalse(html.contains("--data-urlencode"));
        assertEquals(1, html.split("--data-binary", -1).length - 1);
        String form = render(api(RequestBodyObjectBuilder.requestBodyObject().withContent(Collections.singletonMap(
            "application/x-www-form-urlencoded", MediaTypeObjectBuilder.mediaTypeObject().withSchema(schema).build())).build()));
        assertTrue(form.contains("--data-urlencode"));
        assertTrue(form.contains("name=Alice"));
    }

    private static OpenAPIObject api(RequestBodyObject body) {
        return OpenAPIObjectBuilder.openAPIObject().withPaths(PathsObjectBuilder.pathsObject().withPathItemObjects(Collections.singletonMap("/item",
            PathItemObjectBuilder.pathItemObject().withOperations(Collections.singletonMap("post", OperationObjectBuilder.operationObject().withRequestBody(body).build())).build())).build()).build();
    }
    private static String render(OpenAPIObject api) throws Exception {
        StringWriter output = new StringWriter();
        try (BufferedWriter writer = new BufferedWriter(output)) { new HtmlDocumentor(writer, api, "", URI.create("https://example.test")).writeHtml(); }
        return output.toString();
    }
}
