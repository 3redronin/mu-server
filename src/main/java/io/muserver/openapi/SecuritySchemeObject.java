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
    static final List<String> validTypes = asList("apiKey", "http", "oauth2", "openIdConnect");

    public final String type;
    public final String description;
    public final String name;
    public final String in;
    public final String scheme;
    public final String bearerFormat;
    public final OAuthFlowsObject flows;
    public final URI openIdConnectUrl;

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
}
