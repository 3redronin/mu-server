package io.muserver.openapi;

import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static io.muserver.openapi.OfflineOpenApiValidator.*;
import static org.junit.jupiter.api.Assertions.*;

public class OpenApi32OAuthTest {
    @Test public void urlsAreValidatedInEveryContainingSlot() throws Exception {
        String[] slots = {"implicit", "password", "clientCredentials", "authorizationCode", "deviceAuthorization"};
        int[] required = {1, 2, 2, 3, 6};
        for (int slot = 0; slot < slots.length; slot++) {
            for (int mask = 0; mask < 8; mask++) {
                OAuthFlowObject flow = OAuthFlowObjectBuilder.oAuthFlowObject()
                    .withAuthorizationUrl((mask & 1) == 0 ? null : URI.create("/authorize"))
                    .withTokenUrl((mask & 2) == 0 ? null : URI.create("/token"))
                    .withDeviceAuthorizationUrl((mask & 4) == 0 ? null : URI.create("/device"))
                    .withRefreshUrl(URI.create("/refresh")).withScopes(Collections.singletonMap("read", "Read"))
                    .withExtension("x-flow", false).build();
                OAuthFlowsObjectBuilder builder = OAuthFlowsObjectBuilder.oAuthFlowsObject();
                switch (slot) {
                    case 0: builder.withImplicit(flow); break;
                    case 1: builder.withPassword(flow); break;
                    case 2: builder.withClientCredentials(flow); break;
                    case 3: builder.withAuthorizationCode(flow); break;
                    default: builder.withDeviceAuthorization(flow);
                }
                String context = slots[slot] + " URL mask " + mask;
                if (mask != required[slot]) {
                    assertThrows(IllegalArgumentException.class, builder::build, context);
                } else {
                    OAuthFlowsObject flows = builder.build();
                    object("OAuthFlowsObject", json(flows));
                    assertJsonEquals(context, json(flows), json(flows.toBuilder().build()));
                    assertEquals("/refresh", json(flows).getJSONObject(slots[slot]).getString("refreshUrl"));
                    assertFalse(json(flows).getJSONObject(slots[slot]).getBoolean("x-flow"));
                    document(OpenAPIObjectBuilder.openAPIObject().withComponents(ComponentsObjectBuilder.componentsObject()
                        .withSecuritySchemes(Collections.singletonMap("oauth", SecuritySchemeObjectBuilder.securitySchemeObject()
                            .withType("oauth2").withFlows(flows).build())).build()).build());
                }
            }
        }
    }

    @Test public void scopesAndExtensionsAreImmutableSnapshotsAndEmptyFlowsAreValid() throws Exception {
        Map<String, String> scopes = new LinkedHashMap<>();
        scopes.put("read", "Read");
        OAuthFlowObject flow = OAuthFlowObjectBuilder.oAuthFlowObject().withScopes(scopes).build();
        scopes.clear();
        assertEquals(Collections.singletonMap("read", "Read"), flow.scopes());
        assertThrows(UnsupportedOperationException.class, () -> flow.scopes().clear());
        object("OAuthFlowsObject", json(OAuthFlowsObjectBuilder.oAuthFlowsObject().build()));
    }
}
