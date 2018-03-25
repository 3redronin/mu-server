package io.muserver.openapi;

import java.net.URI;
import java.util.Map;

/**
 * Configuration details for a supported OAuth Flow
 */
public class OAuthFlowObjectBuilder {
    private URI authorizationUrl;
    private URI tokenUrl;
    private URI refreshUrl;
    private Map<String, String> scopes;

    /**
     * @param authorizationUrl <strong>REQUIRED</strong>. The authorization URL to be used for this flow.
     * @return The current builder
     */
    public OAuthFlowObjectBuilder withAuthorizationUrl(URI authorizationUrl) {
        this.authorizationUrl = authorizationUrl;
        return this;
    }

    /**
     * @param tokenUrl <strong>REQUIRED</strong>. The token URL to be used for this flow. This MUST be in the form of a URL.
     * @return The current builder
     */
    public OAuthFlowObjectBuilder withTokenUrl(URI tokenUrl) {
        this.tokenUrl = tokenUrl;
        return this;
    }

    /**
     * @param refreshUrl The URL to be used for obtaining refresh tokens. This MUST be in the form of a URL.
     * @return The current builder
     */
    public OAuthFlowObjectBuilder withRefreshUrl(URI refreshUrl) {
        this.refreshUrl = refreshUrl;
        return this;
    }

    /**
     * @param scopes <strong>REQUIRED</strong>. The available scopes for the OAuth2 security scheme. A map between the scope name and a short description for it.
     * @return The current builder
     */
    public OAuthFlowObjectBuilder withScopes(Map<String, String> scopes) {
        this.scopes = scopes;
        return this;
    }

    public OAuthFlowObject build() {
        return new OAuthFlowObject(authorizationUrl, tokenUrl, refreshUrl, scopes);
    }

    /**
     * Creates a builder for an {@link OAuthFlowObject}
     *
     * @return A new builder
     */
    public static OAuthFlowObjectBuilder oAuthFlowObject() {
        return new OAuthFlowObjectBuilder();
    }
}