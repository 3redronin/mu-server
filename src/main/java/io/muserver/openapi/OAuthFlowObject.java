package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see OAuthFlowObjectBuilder
 */
public class OAuthFlowObject implements JsonWriter {

    private final URI authorizationUrl;
    private final URI tokenUrl;
    private final URI refreshUrl;
    private final Map<String, String> scopes;

    OAuthFlowObject(URI authorizationUrl, URI tokenUrl, URI refreshUrl, Map<String, String> scopes) {
        notNull("authorizationUrl", authorizationUrl);
        notNull("tokenUrl", tokenUrl);
        notNull("scopes", scopes);
        this.authorizationUrl = authorizationUrl;
        this.tokenUrl = tokenUrl;
        this.refreshUrl = refreshUrl;
        this.scopes = scopes;
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
     * @return the value described by {@link OAuthFlowObjectBuilder#withAuthorizationUrl}
     */
    public URI authorizationUrl() {
        return authorizationUrl;
    }

    /**
      @return the value described by {@link OAuthFlowObjectBuilder#withTokenUrl}
     */
    public URI tokenUrl() {
        return tokenUrl;
    }

    /**
      @return the value described by {@link OAuthFlowObjectBuilder#withRefreshUrl}
     */
    public URI refreshUrl() {
        return refreshUrl;
    }

    /**
      @return the value described by {@link OAuthFlowObjectBuilder#withScopes}
     */
    public Map<String, String> scopes() {
        return scopes;
    }
}
