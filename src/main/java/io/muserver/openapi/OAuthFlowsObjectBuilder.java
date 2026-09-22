package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Allows configuration of the supported OAuth Flows.
 */
public class OAuthFlowsObjectBuilder {
    private @Nullable OAuthFlowObject deviceAuthorization;
    private @Nullable Map<String, Object> extensions;
    private @Nullable OAuthFlowObject implicit;
    private @Nullable OAuthFlowObject password;
    private @Nullable OAuthFlowObject clientCredentials;
    private @Nullable OAuthFlowObject authorizationCode;

    /**
     *
     * @param implicit Configuration for the OAuth Implicit flow
     *
     * @return The current builder
     */
    public OAuthFlowsObjectBuilder withImplicit(@Nullable OAuthFlowObject implicit) {
        this.implicit = implicit;
        return this;
    }

    /**
     *
     * @param password Configuration for the OAuth Resource Owner Password flow
     *
     * @return The current builder
     */
    public OAuthFlowsObjectBuilder withPassword(@Nullable OAuthFlowObject password) {
        this.password = password;
        return this;
    }

    /**
     *
     * @param clientCredentials Configuration for the OAuth Client Credentials flow. Previously called <code>application</code> in OpenAPI 2.0.
     *
     * @return The current builder
     */
    public OAuthFlowsObjectBuilder withClientCredentials(@Nullable OAuthFlowObject clientCredentials) {
        this.clientCredentials = clientCredentials;
        return this;
    }

    /**
     *
     * @param authorizationCode Configuration for the OAuth Authorization Code flow. Previously called <code>accessCode</code> in OpenAPI 2.0.
     *
     * @return The current builder
     */
    public OAuthFlowsObjectBuilder withAuthorizationCode(@Nullable OAuthFlowObject authorizationCode) {
        this.authorizationCode = authorizationCode;
        return this;
    }

    /**
     * @return A new object
     */
    public OAuthFlowsObject build() {
        return new OAuthFlowsObject(implicit, password, clientCredentials, authorizationCode, deviceAuthorization, extensions);
    }

    /**
     * Creates a builder for an {@link OAuthFlowsObject}
     *
     * @return A new builder
     */
    public static OAuthFlowsObjectBuilder oAuthFlowsObject() {
        return new OAuthFlowsObjectBuilder();
    }
    /**
     * @param value the extensions value
     * @return this builder */
    public OAuthFlowsObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public OAuthFlowsObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
    /**
     * Configures OAuth device authorization for clients such as televisions or command-line tools,
     * where the user completes authorization on a separate device.
     * The flow requires device authorization and token URLs; an authorization URL is not allowed.
     *
     * @param value the device authorization flow configuration, or null to omit it
     * @return this builder
     */
    public OAuthFlowsObjectBuilder withDeviceAuthorization(@Nullable OAuthFlowObject value) { this.deviceAuthorization = value; return this; }
}
