package io.muserver.openapi;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static io.muserver.openapi.OfflineOpenApiValidator.*;

public class OpenApi32MediaTypeKeyTest {
    @Test public void inlineMediaTypesRejectInvalidComponentKeys() {
        for (String name : Arrays.asList("bad/key", "bad key", "", "bad#key")) {
            assertThrows(name, IllegalArgumentException.class, () -> ComponentsObjectBuilder.componentsObject()
                .withMediaTypes(Collections.singletonMap(name, MediaTypeObjectBuilder.mediaTypeObject().build())).build());
        }
    }
    @Test public void referencedMediaTypesRejectInvalidComponentKeys() {
        assertThrows(IllegalArgumentException.class, () -> ComponentsObjectBuilder.componentsObject()
            .withMediaTypesOrReferences(Collections.singletonMap("bad/key", ReferenceOr.reference("#/components/mediaTypes/Good"))).build());
    }
    @Test public void validNamesAndEmptyMapsSurviveCopiesAndValidate() throws Exception {
        Map<String, ReferenceOr<MediaTypeObject>> values = new LinkedHashMap<>();
        values.put("Good-1.0_name", ReferenceOr.inline(MediaTypeObjectBuilder.mediaTypeObject().build()));
        values.put("Alias", ReferenceOr.reference("#/components/mediaTypes/Good-1.0_name"));
        for (Map<String, ReferenceOr<MediaTypeObject>> map : Arrays.asList(values, Collections.<String, ReferenceOr<MediaTypeObject>>emptyMap())) {
            ComponentsObject components = ComponentsObjectBuilder.componentsObject().withMediaTypesOrReferences(map).build();
            assertJsonEquals(json(components), json(components.toBuilder().build()));
            document(OpenAPIObjectBuilder.openAPIObject().withComponents(components).build());
        }
    }
}
