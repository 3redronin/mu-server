package io.muserver.openapi;

import org.junit.jupiter.api.Test;
import static io.muserver.openapi.OfflineOpenApiValidator.*;
import static org.junit.jupiter.api.Assertions.*;

public class OpenApi32CookieTest {
    @Test public void cookieDefaultsAreTrueAndOmittedForEveryCookieStyle() throws Exception {
        for (String style : new String[] {null, "form", "cookie"}) {
            for (Boolean explode : new Boolean[] {null, true}) {
                ParameterObject parameter = cookie(style).withExplode(explode).build();
                assertTrue(parameter.explode());
                assertTrue(parameter.toBuilder().build().explode());
                assertFalse(json(parameter).has("explode"));
                assertJsonEquals(json(parameter), json(parameter.toBuilder().build()));
                object("ParameterObject", json(parameter));
            }
        }
    }
    @Test public void explicitFalseIsInvalidForEveryCookieStyle() {
        for (String style : new String[] {null, "form", "cookie"}) {
            assertThrows(IllegalArgumentException.class, () -> cookie(style).withExplode(false).build(), "style=" + style);
        }
    }
    private static ParameterObjectBuilder cookie(String style) {
        return ParameterObjectBuilder.parameterObject().withName("session").withIn("cookie")
            .withStyle(style).withSchema(SchemaObjectBuilder.schemaObject().withType("object").build());
    }
}
