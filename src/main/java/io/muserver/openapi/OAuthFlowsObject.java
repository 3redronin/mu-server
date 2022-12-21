package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;

import static io.muserver.openapi.Jsonizer.append;

/**
 * @see OAuthFlowsObjectBuilder
 */
public class OAuthFlowsObject implements JsonWriter {

    /**
     * @deprecated use {@link #implicit()} instead
     */
    @Deprecated
    public final OAuthFlowObject implicit;
    /**
      @deprecated use {@link #password()} instead
     */
    @Deprecated
    public final OAuthFlowObject password;
    /**
      @deprecated use {@link #clientCredentials()} instead
     */
    @Deprecated
    public final OAuthFlowObject clientCredentials;
    /**
      @deprecated use {@link #authorizationCode()} instead
     */
    @Deprecated
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

    /**
     * @return the value described by {@link OAuthFlowsObjectBuilder#withImplicit}
     */
    public OAuthFlowObject implicit() {
        return implicit;
    }

    /**
      @return the value described by {@link OAuthFlowsObjectBuilder#withPassword}
     */
    public OAuthFlowObject password() {
        return password;
    }

    /**
      @return the value described by {@link OAuthFlowsObjectBuilder#withClientCredentials}
     */
    public OAuthFlowObject clientCredentials() {
        return clientCredentials;
    }

    /**
      @return the value described by {@link OAuthFlowsObjectBuilder#withAuthorizationCode}
     */
    public OAuthFlowObject authorizationCode() {
        return authorizationCode;
    }
}
