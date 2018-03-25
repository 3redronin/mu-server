package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;

import static io.muserver.openapi.Jsonizer.append;

/**
 * @see OAuthFlowsObjectBuilder
 */
public class OAuthFlowsObject implements JsonWriter {

    public final OAuthFlowObject implicit;
    public final OAuthFlowObject password;
    public final OAuthFlowObject clientCredentials;
    public final OAuthFlowObject authorizationCode;

    OAuthFlowsObject(OAuthFlowObject implicit, OAuthFlowObject password, OAuthFlowObject clientCredentials, OAuthFlowObject authorizationCode) {
        this.implicit = implicit;
        this.password = password;
        this.clientCredentials = clientCredentials;
        this.authorizationCode = authorizationCode;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "implicit", implicit, isFirst);
        isFirst = append(writer, "password", password, isFirst);
        isFirst = append(writer, "clientCredentials", clientCredentials, isFirst);
        isFirst = append(writer, "authorizationCode", authorizationCode, isFirst);
        writer.write('}');

    }
}
