package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

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
    private final java.net.@Nullable URI deviceAuthorizationUrl;
    private final Map<String, Object> extensions;

    private final @Nullable URI authorizationUrl;
    private final @Nullable URI tokenUrl;
    private final @Nullable URI refreshUrl;
    private final Map<String, String> scopes;

    OAuthFlowObject(@Nullable URI authorizationUrl, @Nullable URI tokenUrl, @Nullable URI refreshUrl, @Nullable Map<String, String> scopes, java.net.@Nullable URI deviceAuthorizationUrl, @Nullable Map<String, Object> extensions) {
        this.deviceAuthorizationUrl = deviceAuthorizationUrl;
        this.extensions = Extensions.copy(extensions);
        this.authorizationUrl = authorizationUrl;
        this.tokenUrl = tokenUrl;
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
        isFirst = Jsonizer.append(writer, "deviceAuthorizationUrl", deviceAuthorizationUrl, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write('}');
    }

    /**
     * @return the value described by {@link OAuthFlowObjectBuilder#withAuthorizationUrl}
     */
    public @Nullable URI authorizationUrl() {
        return authorizationUrl;
    }

    /**
      @return the value described by {@link OAuthFlowObjectBuilder#withTokenUrl}
     */
    public @Nullable URI tokenUrl() {
        return tokenUrl;
    }

    /**
      @return the value described by {@link OAuthFlowObjectBuilder#withRefreshUrl}
     */
    public @Nullable URI refreshUrl() {
        return refreshUrl;
    }

    /**
      @return the value described by {@link OAuthFlowObjectBuilder#withScopes}
     */
    public Map<String, String> scopes() {
        return scopes;
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public OAuthFlowObjectBuilder toBuilder() {
        return new OAuthFlowObjectBuilder()
            .withDeviceAuthorizationUrl(deviceAuthorizationUrl).withExtensions(extensions).withAuthorizationUrl(authorizationUrl).withTokenUrl(tokenUrl).withRefreshUrl(refreshUrl).withScopes(scopes);
    }
    /**
     * @return the device authorization endpoint URL, or null when omitted
     * @see OAuthFlowObjectBuilder#withDeviceAuthorizationUrl
     */
    public java.net.@Nullable URI deviceAuthorizationUrl() { return deviceAuthorizationUrl; }
}
