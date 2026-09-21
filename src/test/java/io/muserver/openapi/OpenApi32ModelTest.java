package io.muserver.openapi;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static io.muserver.openapi.OfflineOpenApiValidator.*;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;

public class OpenApi32ModelTest {
    @Test public void contentReferencesPreserveIdentityAndRejectInlineAccess() throws Exception {
        Map<String, ReferenceOr<MediaTypeObject>> content = Collections.singletonMap("text/event-stream", ReferenceOr.reference("#/components/mediaTypes/Events"));
        ResponseObject response = ResponseObjectBuilder.responseObject().withSummary("Events").withContentOrReferences(content).build();
        assertThrows(IllegalStateException.class, response::content);
        assertEquals(json(response), json(response.toBuilder().build()));
        assertEquals(json(response), json(ResponseObjectBuilder.mergeResponses(response, response).build()));
        OpenAPIObject api = OpenAPIObjectBuilder.openAPIObject().withSelf("https://example.test/api.json")
            .withComponents(ComponentsObjectBuilder.componentsObject().withMediaTypes(Collections.singletonMap("Events",
                MediaTypeObjectBuilder.mediaTypeObject().withItemSchema(schemaObject().withType("object").build()).build()))
                .withResponses(Collections.singletonMap("Events", response)).build()).build();
        document(api);
    }

    @Test public void examplesAndXmlRejectConflictingRepresentations() {
        assertThrows(IllegalArgumentException.class, () -> ExampleObjectBuilder.exampleObject().withValue(false).withDataValue(false).build());
        assertThrows(IllegalArgumentException.class, () -> ExampleObjectBuilder.exampleObject().withSerializedValue("").withExternalValue(java.net.URI.create("example.txt")).build());
        assertThrows(IllegalArgumentException.class, () -> XmlObjectBuilder.xmlObject().withNodeType("text").withAttribute(false).build());
        assertThrows(IllegalArgumentException.class, () -> XmlObjectBuilder.xmlObject().withNodeType("invalid").build());
        assertThrows(IllegalArgumentException.class, () -> MediaTypeObjectBuilder.mediaTypeObject().withEncoding(Collections.emptyMap()).withPrefixEncoding(Collections.emptyList()).build());
    }

    @Test public void querystringConflictsAreCheckedAcrossPathAndOperation() throws Exception {
        ParameterObject querystring = ParameterObjectBuilder.parameterObject().withName("search").withIn("querystring")
            .withContent(Collections.singletonMap("application/json", MediaTypeObjectBuilder.mediaTypeObject().build())).build();
        ParameterObject query = ParameterObjectBuilder.parameterObject().withName("q").withIn("query").withSchema(schemaObject().build()).build();
        OperationObject operation = OperationObjectBuilder.operationObject().withParameters(Collections.singletonList(query)).build();
        assertThrows(IllegalArgumentException.class, () -> PathItemObjectBuilder.pathItemObject().withParameters(Collections.singletonList(querystring))
            .withOperations(Collections.singletonMap("query", operation)).build());
        ParameterObject cookie = ParameterObjectBuilder.parameterObject().withName("session").withIn("cookie").withStyle("cookie")
            .withExplode(false).withSchema(schemaObject().build()).build();
        assertFalse(cookie.explode()); assertTrue(json(cookie).has("explode"));
        object("ParameterObject", json(cookie)); object("ParameterObject", json(querystring));
        object("ParameterObject", json(ParameterObjectBuilder.parameterObject().withName("path").withIn("path").withRequired(true)
            .withAllowReserved(true).withSchema(schemaObject().build()).build()));
    }

    @Test public void streamingFixturesKeepWholeStreamAndItemContracts() throws Exception {
        for (String mediaType : Arrays.asList("application/jsonl", "application/x-ndjson", "application/json-seq", "multipart/mixed")) {
            MediaTypeObjectBuilder builder = MediaTypeObjectBuilder.mediaTypeObject().withDescription(mediaType)
                .withSchema(schemaObject().withType("string").build()).withItemSchema(schemaObject().withType("integer").build());
            if (mediaType.startsWith("multipart")) builder.withPrefixEncoding(Collections.singletonList(EncodingObjectBuilder.encodingObject().withContentType("application/json").build()))
                .withItemEncoding(EncodingObjectBuilder.encodingObject().withContentType("text/plain").build());
            MediaTypeObject value = builder.build();
            object("MediaTypeObject", json(value)); assertEquals(json(value), json(value.toBuilder().build()));
            MediaTypeObject merged = MediaTypeObjectBuilder.mergeMediaTypes(value, MediaTypeObjectBuilder.mediaTypeObject()
                .withItemSchema(schemaObject().withType("boolean").build()).build()).build();
            assertEquals(2, merged.itemSchema().anyOf().size());
        }
    }
}
