package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

/**
 * Allows configuration of the supported OAuth Flows.
 */
public class OAuthFlowsObjectBuilder {
    private @Nullable OAuthFlowObject implicit;
    private @Nullable OAuthFlowObject password;
    private @Nullable OAuthFlowObject clientCredentials;
    private @Nullable OAuthFlowObject authorizationCode;

    /**
     * Creates an empty OAuth flows object builder.
     */
    public OAuthFlowsObjectBuilder() {
    }

    /**
     * Sets the implicit OAuth flow configuration.
     *
     * @param implicit Configuration for the OAuth Implicit flow
     * @return The current builder
     */
    public OAuthFlowsObjectBuilder withImplicit(@Nullable OAuthFlowObject implicit) {
        this.implicit = implicit;
        return this;
    }

    /**
     * Sets the resource-owner-password OAuth flow configuration.
     *
     * @param password Configuration for the OAuth Resource Owner Password flow
     * @return The current builder
     */
    public OAuthFlowsObjectBuilder withPassword(@Nullable OAuthFlowObject password) {
        this.password = password;
        return this;
    }

    /**
     * Sets the client-credentials OAuth flow configuration.
     *
     * @param clientCredentials Configuration for the OAuth Client Credentials flow. Previously called <code>application</code> in OpenAPI 2.0.
     * @return The current builder
     */
    public OAuthFlowsObjectBuilder withClientCredentials(@Nullable OAuthFlowObject clientCredentials) {
        this.clientCredentials = clientCredentials;
        return this;
    }

    /**
     * Sets the authorization-code OAuth flow configuration.
     *
     * @param authorizationCode Configuration for the OAuth Authorization Code flow. Previously called <code>accessCode</code> in OpenAPI 2.0.
     * @return The current builder
     */
    public OAuthFlowsObjectBuilder withAuthorizationCode(@Nullable OAuthFlowObject authorizationCode) {
        this.authorizationCode = authorizationCode;
        return this;
    }

    /**
     * Builds an OAuth flows object from the configured values.
     *
     * @return A new object
     */
    public OAuthFlowsObject build() {
        return new OAuthFlowsObject(implicit, password, clientCredentials, authorizationCode);
    }

    /**
     * Creates a builder for an {@link OAuthFlowsObject}
     *
     * @return A new builder
     */
    public static OAuthFlowsObjectBuilder oAuthFlowsObject() {
        return new OAuthFlowsObjectBuilder();
    }
}