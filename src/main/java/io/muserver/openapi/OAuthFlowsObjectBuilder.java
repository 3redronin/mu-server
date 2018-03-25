package io.muserver.openapi;

/**
 * Allows configuration of the supported OAuth Flows.
 */
public class OAuthFlowsObjectBuilder {
    private OAuthFlowObject implicit;
    private OAuthFlowObject password;
    private OAuthFlowObject clientCredentials;
    private OAuthFlowObject authorizationCode;

    /**
     * @param implicit Configuration for the OAuth Implicit flow
     * @return The current builder
     */
    public OAuthFlowsObjectBuilder withImplicit(OAuthFlowObject implicit) {
        this.implicit = implicit;
        return this;
    }

    /**
     * @param password Configuration for the OAuth Resource Owner Password flow
     * @return The current builder
     */
    public OAuthFlowsObjectBuilder withPassword(OAuthFlowObject password) {
        this.password = password;
        return this;
    }

    /**
     * @param clientCredentials Configuration for the OAuth Client Credentials flow. Previously called <code>application</code> in OpenAPI 2.0.
     * @return The current builder
     */
    public OAuthFlowsObjectBuilder withClientCredentials(OAuthFlowObject clientCredentials) {
        this.clientCredentials = clientCredentials;
        return this;
    }

    /**
     * @param authorizationCode Configuration for the OAuth Authorization Code flow. Previously called <code>accessCode</code> in OpenAPI 2.0.
     * @return The current builder
     */
    public OAuthFlowsObjectBuilder withAuthorizationCode(OAuthFlowObject authorizationCode) {
        this.authorizationCode = authorizationCode;
        return this;
    }

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