package io.muserver.rest;

import io.muserver.openapi.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static io.muserver.openapi.SchemaObjectBuilder.schemaObject;

public class OpenApi32ReferencesTest {
    @Test public void selfRelativeReferencesResolveAndCyclesTerminate() {
        SchemaObject value = schemaObject().withType("integer").build();
        MediaTypeObject media = MediaTypeObjectBuilder.mediaTypeObject().withItemSchema(value).build();
        OpenAPIObject api = OpenAPIObjectBuilder.openAPIObject().withSelf("https://example.test/docs/api.json")
            .withComponents(ComponentsObjectBuilder.componentsObject()
                .withSchemas(Map.of("Value", value, "Cycle", schemaObject().withRef("api.json#/components/schemas/Cycle").build()))
                .withMediaTypes(Map.of("Events", media)).build()).build();
        DocumentationReferences references = new DocumentationReferences(api);
        assertSame(value, references.schema(schemaObject().withRef("api.json#/components/schemas/Value").build()));
        assertSame(media, references.resolve(ReferenceOr.reference("https://example.test/docs/api.json#/components/mediaTypes/Events"), MediaTypeObject.class));
        assertNull(references.schema(schemaObject().withRef("#/components/schemas/Cycle").build()));
        assertNull(references.schema(schemaObject().withRef("other.json#/components/schemas/Value").build()));
    }
}
