package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * Describes one OAuth 2.0 authorization flow.
 *
 * @see OAuthFlowObjectBuilder
 */
public class OAuthFlowObject implements JsonWriter {

    private final URI authorizationUrl;
    private final URI tokenUrl;
    private final @Nullable URI refreshUrl;
    private final Map<String, String> scopes;

    OAuthFlowObject(@Nullable URI authorizationUrl, @Nullable URI tokenUrl, @Nullable URI refreshUrl, @Nullable Map<String, String> scopes) {
        notNull("authorizationUrl", authorizationUrl);
        this.authorizationUrl = java.util.Objects.requireNonNull(authorizationUrl);
        notNull("tokenUrl", tokenUrl);
        this.tokenUrl = java.util.Objects.requireNonNull(tokenUrl);
        this.refreshUrl = refreshUrl;
        notNull("scopes", scopes);
        this.scopes = java.util.Objects.requireNonNull(scopes);
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "authorizationUrl", authorizationUrl, isFirst);
        isFirst = append(writer, "tokenUrl", tokenUrl, isFirst);
        isFirst = append(writer, "refreshUrl", refreshUrl, isFirst);
        isFirst = append(writer, "scopes", scopes, isFirst);
        writer.write('}');
    }

    /**
     * Gets the authorization URL for the flow.
     *
     * @return the value described by {@link OAuthFlowObjectBuilder#withAuthorizationUrl}
     */
    public URI authorizationUrl() {
        return authorizationUrl;
    }

    /**
     * Gets the token URL for the flow.
     *
     * @return the value described by {@link OAuthFlowObjectBuilder#withTokenUrl}
     */
    public URI tokenUrl() {
        return tokenUrl;
    }

    /**
     * Gets the refresh URL for the flow.
     *
     * @return the value described by {@link OAuthFlowObjectBuilder#withRefreshUrl}
     */
    public @Nullable URI refreshUrl() {
        return refreshUrl;
    }

    /**
     * Gets the scopes advertised by the flow.
     *
     * @return the value described by {@link OAuthFlowObjectBuilder#withScopes}
     */
    public Map<String, String> scopes() {
        return scopes;
    }
}
