package io.muserver.openapi;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static io.muserver.openapi.OfflineOpenApiValidator.*;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;
import static org.junit.jupiter.api.Assertions.*;

public class OpenApi32BehaviorTest {
    @Test public void examplesPreserveFalseEmptyAndNullButRejectConflictingRepresentations() throws Exception {
        for (Object data : Arrays.asList(false, "", JsonNull.INSTANCE, Collections.emptyList(), Collections.emptyMap())) {
            ExampleObject example = ExampleObjectBuilder.exampleObject().withDataValue(data).withSerializedValue("").build();
            object("ExampleObject", json(example));
            assertTrue(json(example).has("dataValue"));
            assertEquals("", json(example).getString("serializedValue"));
            assertJsonEquals(json(example), json(example.toBuilder().build()));
            assertThrows(IllegalArgumentException.class, () -> example.toBuilder().withValue(false).build());
            assertThrows(IllegalArgumentException.class, () -> example.toBuilder().withExternalValue(URI.create("example.json")).build());
        }
        ExampleObject external = ExampleObjectBuilder.exampleObject().withDataValue(false).withExternalValue(URI.create("example.json")).build();
        object("ExampleObject", json(external));
    }

    @Test public void serverMetadataDoesNotAlterUrlTemplatesAndEmptyNamesArePreserved() throws Exception {
        Map<String, ServerVariableObject> variables = new LinkedHashMap<>();
        variables.put("version", ServerVariableObjectBuilder.serverVariableObject().withDefaultValue("v3").build());
        ServerObject server = ServerObjectBuilder.serverObject().withUrl("/{version}").withName("").withVariables(variables).build();
        variables.clear();
        assertEquals("/{version}", server.url());
        assertEquals("", server.name());
        assertEquals(1, server.variables().size());
        assertThrows(UnsupportedOperationException.class, () -> server.variables().clear());
        assertJsonEquals(json(server), json(server.toBuilder().build()));
        object("ServerObject", json(server));
        assertFalse(json(server.toBuilder().withName(null).build()).has("name"));
    }

    @Test public void tagHierarchyRejectsMissingParentsAndCyclesButAllowsForwardDeclarations() throws Exception {
        TagObject child = TagObjectBuilder.tagObject().withName("child").withParent("parent").withSummary("").withKind("custom").build();
        TagObject parent = TagObjectBuilder.tagObject().withName("parent").build();
        OpenAPIObjectBuilder builder = OpenAPIObjectBuilder.openAPIObject().withComponents(ComponentsObjectBuilder.componentsObject().build());
        assertThrows(IllegalArgumentException.class, () -> builder.withTags(List.of(child)).build());
        assertThrows(IllegalArgumentException.class, () -> builder.withTags(List.of(child, parent.toBuilder().withParent("child").build())).build());
        assertThrows(IllegalArgumentException.class, () -> builder.withTags(List.of(parent.toBuilder().withParent("parent").build())).build());
        List<TagObject> tags = new ArrayList<>(Arrays.asList(child, parent));
        OpenAPIObject api = builder.withTags(tags).build();
        tags.clear();
        assertEquals(2, api.tags().size());
        assertThrows(UnsupportedOperationException.class, () -> api.tags().clear());
        document(api);
        assertJsonEquals(json(api), json(api.toBuilder().build()));
    }

    @Test public void newReferencePositionsKeepSnapshotsAndFailClearlyForInlineAccess() throws Exception {
        Map<String, ReferenceOr<MediaTypeObject>> refs = new LinkedHashMap<>();
        refs.put("text/plain", ReferenceOr.reference("#/components/mediaTypes/Text"));
        ResponseObject response = ResponseObjectBuilder.responseObject().withContentOrReferences(refs).build();
        RequestBodyObject body = RequestBodyObjectBuilder.requestBodyObject().withContentOrReferences(refs).build();
        ParameterObject parameter = ParameterObjectBuilder.parameterObject().withName("q").withIn("querystring").withContentOrReferences(refs).build();
        HeaderObject header = HeaderObjectBuilder.headerObject().withContentOrReferences(refs).build();
        ComponentsObject components = ComponentsObjectBuilder.componentsObject().withMediaTypesOrReferences(Map.of("Alias", refs.get("text/plain"),
            "Text", ReferenceOr.inline(MediaTypeObjectBuilder.mediaTypeObject().withSchema(schemaObject().withType("string").build()).build())))
            .withResponses(Map.of("Response", response)).withRequestBodies(Map.of("Body", body))
            .withParameters(Map.of("Query", parameter)).withHeaders(Map.of("Header", header)).build();
        refs.clear();
        assertThrows(IllegalStateException.class, response::content);
        assertThrows(IllegalStateException.class, body::content);
        assertThrows(IllegalStateException.class, parameter::content);
        assertThrows(IllegalStateException.class, header::content);
        assertThrows(IllegalStateException.class, components::mediaTypes);
        assertThrows(UnsupportedOperationException.class, () -> body.contentOrReferences().clear());
        assertJsonEquals(json(body), json(body.toBuilder().build()));
        assertJsonEquals(json(parameter), json(parameter.toBuilder().build()));
        assertJsonEquals(json(header), json(header.toBuilder().build()));
        assertJsonEquals(json(components), json(components.toBuilder().build()));
        document(OpenAPIObjectBuilder.openAPIObject().withComponents(components).build());
        ResponseObject other = response.toBuilder().withContentOrReferences(Map.of("text/plain", ReferenceOr.reference("#/components/mediaTypes/Other"))).build();
        assertThrows(IllegalArgumentException.class, () -> ResponseObjectBuilder.mergeResponses(response, other));
    }

    @Test public void encodingCombinationsAreCheckedEvenForEmptyCollectionsAndAreImmutable() throws Exception {
        EncodingObject part = EncodingObjectBuilder.encodingObject().withContentType("text/plain").build();
        for (int mask = 0; mask < 8; mask++) {
            Map<String, EncodingObject> named = (mask & 1) == 0 ? null : Collections.emptyMap();
            List<EncodingObject> prefix = (mask & 2) == 0 ? null : Collections.emptyList();
            EncodingObject item = (mask & 4) == 0 ? null : part;
            EncodingObjectBuilder encoding = EncodingObjectBuilder.encodingObject().withEncoding(named).withPrefixEncoding(prefix).withItemEncoding(item);
            MediaTypeObjectBuilder media = MediaTypeObjectBuilder.mediaTypeObject().withEncoding(named).withPrefixEncoding(prefix).withItemEncoding(item);
            if ((mask & 1) != 0 && (mask & 6) != 0) {
                assertThrows(IllegalArgumentException.class, encoding::build);
                assertThrows(IllegalArgumentException.class, media::build);
            } else {
                object("EncodingObject", json(encoding.build()));
                object("MediaTypeObject", json(media.build()));
                assertJsonEquals(json(media.build()), json(media.build().toBuilder().build()));
            }
        }
        List<EncodingObject> prefix = new ArrayList<>(List.of(part));
        MediaTypeObject media = MediaTypeObjectBuilder.mediaTypeObject().withPrefixEncoding(prefix).build();
        prefix.clear();
        assertEquals(1, media.prefixEncoding().size());
        assertThrows(UnsupportedOperationException.class, () -> media.prefixEncoding().clear());
    }

    @Test public void xmlNodeTypesRejectEveryExplicitLegacyFlag() throws Exception {
        for (String node : Arrays.asList("element", "attribute", "text", "cdata", "none")) {
            XmlObject xml = XmlObjectBuilder.xmlObject().withNodeType(node).build();
            object("XmlObject", json(xml));
            assertJsonEquals(json(xml), json(xml.toBuilder().build()));
            for (boolean flag : new boolean[] {false, true}) {
                assertThrows(IllegalArgumentException.class, () -> xml.toBuilder().withAttribute(flag).build());
                assertThrows(IllegalArgumentException.class, () -> xml.toBuilder().withWrapped(flag).build());
            }
        }
        XmlObject legacy = XmlObjectBuilder.xmlObject().withAttribute(false).withWrapped(false).build();
        assertTrue(json(legacy).has("attribute"));
        assertTrue(json(legacy).has("wrapped"));
    }

    @Test public void discriminatorFallbackIsAnAnnotationAndDoesNotChangeValidation() throws Exception {
        SchemaObject alternatives = schemaObject().withAnyOf(Arrays.asList(schemaObject().withType("integer").build(), schemaObject().withType("string").build()))
            .withDiscriminator(DiscriminatorObjectBuilder.discriminatorObject().withPropertyName("kind").withDefaultMapping("Fallback").build()).build();
        object("SchemaObject", json(alternatives));
        accepts(alternatives, "123", true);
        accepts(alternatives, "false", false);
        assertJsonEquals(json(alternatives), json(alternatives.toBuilder().build()));
    }

    @Test public void securityMetadataIsContextualAndExplicitFalseSurvives() throws Exception {
        SecuritySchemeObject oauth = SecuritySchemeObjectBuilder.securitySchemeObject().withType("oauth2")
            .withFlows(OAuthFlowsObjectBuilder.oAuthFlowsObject().build()).withOauth2MetadataUrl(URI.create("https://example.test/oauth"))
            .withDeprecated(false).build();
        object("SecuritySchemeObject", json(oauth));
        assertFalse(json(oauth).getBoolean("deprecated"));
        assertJsonEquals(json(oauth), json(oauth.toBuilder().build()));
        assertThrows(IllegalArgumentException.class, () -> oauth.toBuilder().withType("http").withScheme("bearer").build());
    }
}
