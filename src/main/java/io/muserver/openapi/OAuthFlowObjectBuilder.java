package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * Configuration details for a supported OAuth Flow
 */
public class OAuthFlowObjectBuilder {
    private @Nullable Map<String, Object> extensions;
    private @Nullable URI authorizationUrl;
    private @Nullable URI tokenUrl;
    private @Nullable URI refreshUrl;
    private @Nullable Map<String, String> scopes;

    /**
     *
     * @param authorizationUrl The authorization URL, required for implicit and authorization-code flows.
     *
     * @return The current builder
     */
    public OAuthFlowObjectBuilder withAuthorizationUrl(@Nullable URI authorizationUrl) {
        this.authorizationUrl = authorizationUrl;
        return this;
    }

    /**
     *
     * @param tokenUrl The token URL, required for password, client-credentials, and authorization-code flows.
     *
     * @return The current builder
     */
    public OAuthFlowObjectBuilder withTokenUrl(@Nullable URI tokenUrl) {
        this.tokenUrl = tokenUrl;
        return this;
    }

    /**
     *
     * @param refreshUrl The URL to be used for obtaining refresh tokens. This MUST be in the form of a URL.
     *
     * @return The current builder
     */
    public OAuthFlowObjectBuilder withRefreshUrl(@Nullable URI refreshUrl) {
        this.refreshUrl = refreshUrl;
        return this;
    }

    /**
     *
     * @param scopes <strong>REQUIRED</strong>. The available scopes for the OAuth2 security scheme. A map between the scope name and a short description for it.
     *
     * @return The current builder
     */
    public OAuthFlowObjectBuilder withScopes(Map<String, String> scopes) {
        this.scopes = scopes;
        return this;
    }

    /**
     * @return A new object
     */
    public OAuthFlowObject build() {
        return new OAuthFlowObject(authorizationUrl, tokenUrl, refreshUrl, immutable(scopes), extensions);
    }

    /**
     * Creates a builder for an {@link OAuthFlowObject}
     *
     * @return A new builder
     */
    public static OAuthFlowObjectBuilder oAuthFlowObject() {
        return new OAuthFlowObjectBuilder();
    }
    /**
     * @param value the extensions value
     * @return this builder */
    public OAuthFlowObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public OAuthFlowObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
}
