package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;

import static io.muserver.openapi.Jsonizer.append;

/**
 * Groups the OAuth 2.0 authorization flows supported by an API.
 *
 * @see OAuthFlowsObjectBuilder
 */
public class OAuthFlowsObject implements JsonWriter {
    private final @Nullable OAuthFlowObject deviceAuthorization;
    private final Map<String, Object> extensions;

    private final @Nullable OAuthFlowObject implicit;
    private final @Nullable OAuthFlowObject password;
    private final @Nullable OAuthFlowObject clientCredentials;
    private final @Nullable OAuthFlowObject authorizationCode;

    OAuthFlowsObject(@Nullable OAuthFlowObject implicit, @Nullable OAuthFlowObject password, @Nullable OAuthFlowObject clientCredentials, @Nullable OAuthFlowObject authorizationCode, @Nullable OAuthFlowObject deviceAuthorization, @Nullable Map<String, Object> extensions) {
        this.deviceAuthorization = deviceAuthorization;
        this.extensions = Extensions.copy(extensions);
        check("implicit", implicit, true, false, false);
        check("password", password, false, true, false);
        check("clientCredentials", clientCredentials, false, true, false);
        check("authorizationCode", authorizationCode, true, true, false);
        check("deviceAuthorization", deviceAuthorization, false, true, true);
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
        isFirst = Jsonizer.append(writer, "deviceAuthorization", deviceAuthorization, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write('}');

    }

    /**
     * Gets the implicit OAuth flow configuration.
     *
     * @return the value described by {@link OAuthFlowsObjectBuilder#withImplicit}
     */
    public @Nullable OAuthFlowObject implicit() {
        return implicit;
    }

    /**
     * Gets the resource-owner-password OAuth flow configuration.
     *
     * @return the value described by {@link OAuthFlowsObjectBuilder#withPassword}
     */
    public @Nullable OAuthFlowObject password() {
        return password;
    }

    /**
     * Gets the client-credentials OAuth flow configuration.
     *
     * @return the value described by {@link OAuthFlowsObjectBuilder#withClientCredentials}
     */
    public @Nullable OAuthFlowObject clientCredentials() {
        return clientCredentials;
    }

    /**
     * Gets the authorization-code OAuth flow configuration.
     *
     * @return the value described by {@link OAuthFlowsObjectBuilder#withAuthorizationCode}
     */
    public @Nullable OAuthFlowObject authorizationCode() {
        return authorizationCode;
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public OAuthFlowsObjectBuilder toBuilder() {
        return new OAuthFlowsObjectBuilder()
            .withDeviceAuthorization(deviceAuthorization).withExtensions(extensions).withImplicit(implicit).withPassword(password).withClientCredentials(clientCredentials).withAuthorizationCode(authorizationCode);
    }
    private static void check(String slot, @Nullable OAuthFlowObject flow, boolean authorization, boolean token, boolean device) {
        if (flow == null) return;
        checkUrl(slot, "authorizationUrl", flow.authorizationUrl(), authorization);
        checkUrl(slot, "tokenUrl", flow.tokenUrl(), token);
        checkUrl(slot, "deviceAuthorizationUrl", flow.deviceAuthorizationUrl(), device);
    }
    private static void checkUrl(String slot, String field, java.net.@Nullable URI url, boolean required) {
        if ((url != null) != required) {
            throw new IllegalArgumentException(slot + " OAuth flow " + (required ? "requires " : "does not allow ") + field);
        }
    }
    /**
     * @return the device authorization flow configuration, or null when omitted
     * @see OAuthFlowsObjectBuilder#withDeviceAuthorization
     */
    public @Nullable OAuthFlowObject deviceAuthorization() { return deviceAuthorization; }
}
