package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.util.List;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;
import static java.util.Arrays.asList;

/**
 * Describes an authentication or authorization scheme.
 *
 * @see SecuritySchemeObjectBuilder
 */
public class SecuritySchemeObject implements JsonWriter {
    private static final List<String> validTypes = asList("apiKey", "http", "oauth2", "openIdConnect");

    private final String type;
    private final @Nullable String description;
    private final @Nullable String name;
    private final @Nullable String in;
    private final @Nullable String scheme;
    private final @Nullable String bearerFormat;
    private final @Nullable OAuthFlowsObject flows;
    private final @Nullable URI openIdConnectUrl;

    SecuritySchemeObject(@Nullable String type, @Nullable String description, @Nullable String name, @Nullable String in, @Nullable String scheme, @Nullable String bearerFormat, @Nullable OAuthFlowsObject flows, @Nullable URI openIdConnectUrl) {
        notNull("type", type);
        java.util.Objects.requireNonNull(type);
        if (!validTypes.contains(type)) {
            throw new IllegalArgumentException("'type' must be one of " + validTypes + " but was " + type);
        }
        switch (type) {
            case "apiKey":
                notNull("name", name);
                notNull("in", in);
                break;
            case "http":
                notNull("scheme", scheme);
                break;
            case "oauth2":
                notNull("flows", flows);
                break;
            case "openIdConnect":
                notNull("openIdConnectUrl", openIdConnectUrl);
                break;
        }
        this.type = type;
        this.description = description;
        this.name = name;
        this.in = in;
        this.scheme = scheme;
        this.bearerFormat = bearerFormat;
        this.flows = flows;
        this.openIdConnectUrl = openIdConnectUrl;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "type", type, isFirst);
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "name", name, isFirst);
        isFirst = append(writer, "in", in, isFirst);
        isFirst = append(writer, "scheme", scheme, isFirst);
        isFirst = append(writer, "bearerFormat", bearerFormat, isFirst);
        isFirst = append(writer, "flows", flows, isFirst);
        isFirst = append(writer, "openIdConnectUrl", openIdConnectUrl, isFirst);
        writer.write('}');
    }

    /**
     * Gets the security scheme type.
     *
     * @return the value described by {@link SecuritySchemeObjectBuilder#withType}
     */
    public String type() {
        return type;
    }

    /**
     * Gets the security scheme description.
     *
     * @return the value described by {@link SecuritySchemeObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
     * Gets the security scheme name.
     *
     * @return the value described by {@link SecuritySchemeObjectBuilder#withName}
     */
    public @Nullable String name() {
        return name;
    }

    /**
     * Gets where the security scheme value is supplied.
     *
     * @return the value described by {@link SecuritySchemeObjectBuilder#withIn}
     */
    public @Nullable String in() {
        return in;
    }

    /**
     * Gets the HTTP authentication scheme name.
     *
     * @return the value described by {@link SecuritySchemeObjectBuilder#withScheme}
     */
    public @Nullable String scheme() {
        return scheme;
    }

    /**
     * Gets the bearer-token format hint.
     *
     * @return the value described by {@link SecuritySchemeObjectBuilder#withBearerFormat}
     */
    public @Nullable String bearerFormat() {
        return bearerFormat;
    }

    /**
     * Gets the OAuth flows configuration.
     *
     * @return the value described by {@link SecuritySchemeObjectBuilder#withFlows}
     */
    public @Nullable OAuthFlowsObject flows() {
        return flows;
    }

    /**
     * Gets the OpenID Connect discovery URL.
     *
     * @return the value described by {@link SecuritySchemeObjectBuilder#withOpenIdConnectUrl}
     */
    public @Nullable URI openIdConnectUrl() {
        return openIdConnectUrl;
    }

    /**
     * Gets the valid security scheme types.
     *
     * @return The types allowed to be passed to {@link SecuritySchemeObjectBuilder#withType(String)}
     */
    public static List<String> validTypes() {
        return validTypes;
    }

}
