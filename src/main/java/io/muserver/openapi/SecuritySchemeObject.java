package io.muserver.openapi;

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
    private static final List<String> validTypes = asList("apiKey", "http", "oauth2", "openIdConnect");

    private final String type;
    private final String description;
    private final String name;
    private final String in;
    private final String scheme;
    private final String bearerFormat;
    private final OAuthFlowsObject flows;
    private final URI openIdConnectUrl;

    SecuritySchemeObject(String type, String description, String name, String in, String scheme, String bearerFormat, OAuthFlowsObject flows, URI openIdConnectUrl) {
        notNull("type", type);
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
     * @return the value described by {@link SecuritySchemeObjectBuilder#withType}
     */
    public String type() {
        return type;
    }

    /**
      @return the value described by {@link SecuritySchemeObjectBuilder#withDescription}
     */
    public String description() {
        return description;
    }

    /**
      @return the value described by {@link SecuritySchemeObjectBuilder#withName}
     */
    public String name() {
        return name;
    }

    /**
      @return the value described by {@link SecuritySchemeObjectBuilder#withIn}
     */
    public String in() {
        return in;
    }

    /**
      @return the value described by {@link SecuritySchemeObjectBuilder#withScheme}
     */
    public String scheme() {
        return scheme;
    }

    /**
      @return the value described by {@link SecuritySchemeObjectBuilder#withBearerFormat}
     */
    public String bearerFormat() {
        return bearerFormat;
    }

    /**
      @return the value described by {@link SecuritySchemeObjectBuilder#withFlows}
     */
    public OAuthFlowsObject flows() {
        return flows;
    }

    /**
      @return the value described by {@link SecuritySchemeObjectBuilder#withOpenIdConnectUrl}
     */
    public URI openIdConnectUrl() {
        return openIdConnectUrl;
    }

    /**
     * @return The types allowed to be passed to {@link SecuritySchemeObjectBuilder#withType(String)}
     */
    public static List<String> validTypes() {
        return validTypes;
    }

}
