package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.util.List;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;
import static java.util.Arrays.asList;

/**
 * @see SecuritySchemeObjectBuilder
 */
public class SecuritySchemeObject implements JsonWriter {
    private final Map<String, Object> extensions;
    private static final List<String> validTypes = asList("apiKey", "http", "oauth2", "openIdConnect", "mutualTLS");

    private final String type;
    private final @Nullable String description;
    private final @Nullable String name;
    private final @Nullable String in;
    private final @Nullable String scheme;
    private final @Nullable String bearerFormat;
    private final @Nullable OAuthFlowsObject flows;
    private final @Nullable URI openIdConnectUrl;

    SecuritySchemeObject(@Nullable String type, @Nullable String description, @Nullable String name, @Nullable String in, @Nullable String scheme, @Nullable String bearerFormat, @Nullable OAuthFlowsObject flows, @Nullable URI openIdConnectUrl, @Nullable Map<String, Object> extensions) {
        this.extensions = Extensions.copy(extensions);
        notNull("type", type);
        java.util.Objects.requireNonNull(type);
        if (!validTypes.contains(type)) {
            throw new IllegalArgumentException("'type' must be one of " + validTypes + " but was " + type);
        }
        switch (type) {
            case "apiKey":
                notNull("name", name);
                notNull("in", in);
                if (!asList("query", "header", "cookie").contains(in)) throw new IllegalArgumentException("Invalid API key location: " + in);
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
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write('}');
    }

    /**
     * @return the value described by {@link SecuritySchemeObjectBuilder#withType}
     */
    public String type() {
        return type;
    }

    /**
      @return the value described by {@link SecuritySchemeObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
      @return the value described by {@link SecuritySchemeObjectBuilder#withName}
     */
    public @Nullable String name() {
        return name;
    }

    /**
      @return the value described by {@link SecuritySchemeObjectBuilder#withIn}
     */
    public @Nullable String in() {
        return in;
    }

    /**
      @return the value described by {@link SecuritySchemeObjectBuilder#withScheme}
     */
    public @Nullable String scheme() {
        return scheme;
    }

    /**
      @return the value described by {@link SecuritySchemeObjectBuilder#withBearerFormat}
     */
    public @Nullable String bearerFormat() {
        return bearerFormat;
    }

    /**
      @return the value described by {@link SecuritySchemeObjectBuilder#withFlows}
     */
    public @Nullable OAuthFlowsObject flows() {
        return flows;
    }

    /**
      @return the value described by {@link SecuritySchemeObjectBuilder#withOpenIdConnectUrl}
     */
    public @Nullable URI openIdConnectUrl() {
        return openIdConnectUrl;
    }

    /**
     * @return The types allowed to be passed to {@link SecuritySchemeObjectBuilder#withType(String)}
     */
    public static List<String> validTypes() {
        return validTypes;
    }

    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public SecuritySchemeObjectBuilder toBuilder() {
        return new SecuritySchemeObjectBuilder()
            .withExtensions(extensions).withType(type).withDescription(description).withName(name).withIn(in).withScheme(scheme).withBearerFormat(bearerFormat).withFlows(flows).withOpenIdConnectUrl(openIdConnectUrl);
    }
}
