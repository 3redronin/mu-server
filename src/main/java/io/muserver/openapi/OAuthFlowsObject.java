package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;

import static io.muserver.openapi.Jsonizer.append;

/**
 * @see OAuthFlowsObjectBuilder
 */
public class OAuthFlowsObject implements JsonWriter {

    private final @Nullable OAuthFlowObject implicit;
    private final @Nullable OAuthFlowObject password;
    private final @Nullable OAuthFlowObject clientCredentials;
    private final @Nullable OAuthFlowObject authorizationCode;

    OAuthFlowsObject(@Nullable OAuthFlowObject implicit, @Nullable OAuthFlowObject password, @Nullable OAuthFlowObject clientCredentials, @Nullable OAuthFlowObject authorizationCode) {
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
    public @Nullable OAuthFlowObject implicit() {
        return implicit;
    }

    /**
      @return the value described by {@link OAuthFlowsObjectBuilder#withPassword}
     */
    public @Nullable OAuthFlowObject password() {
        return password;
    }

    /**
      @return the value described by {@link OAuthFlowsObjectBuilder#withClientCredentials}
     */
    public @Nullable OAuthFlowObject clientCredentials() {
        return clientCredentials;
    }

    /**
      @return the value described by {@link OAuthFlowsObjectBuilder#withAuthorizationCode}
     */
    public @Nullable OAuthFlowObject authorizationCode() {
        return authorizationCode;
    }
}
