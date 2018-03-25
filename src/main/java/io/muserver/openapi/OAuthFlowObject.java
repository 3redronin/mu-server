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

    public final URI authorizationUrl;
    public final URI tokenUrl;
    public final URI refreshUrl;
    public final Map<String, String> scopes;

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
}
