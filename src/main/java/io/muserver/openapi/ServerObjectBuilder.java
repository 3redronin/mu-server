package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * An object representing a Server.
 */
public class ServerObjectBuilder {
    private @Nullable String url;
    private @Nullable String description;
    private @Nullable Map<String, ServerVariableObject> variables;

    /**
     * Creates an empty server object builder.
     */
    public ServerObjectBuilder() {
    }

    /**
     * Sets the server URL template.
     *
     * @param url <strong>REQUIRED</strong>. A URL to the target host.  This URL supports Server Variables and MAY be relative, to indicate
     * that the host location is relative to the location where the OpenAPI document is being served. Variable substitutions will
     * be made when a variable is named in <code>{</code>brackets<code>}</code>.
     * @return The current builder
     */
    public ServerObjectBuilder withUrl(String url) {
        this.url = url;
        return this;
    }

    /**
     * Sets the server description.
     *
     * @param description An optional string describing the host designated by the URL. CommonMark syntax MAY be used for rich text representation.
     * @return The current builder
     */
    public ServerObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     * Sets the server URL variables.
     *
     * @param variables A map between a variable name and its value. The value is used for substitution in the server's URL template.
     * @return The current builder
     */
    public ServerObjectBuilder withVariables(@Nullable Map<String, ServerVariableObject> variables) {
        this.variables = variables;
        return this;
    }

    /**
     * Builds a server object from the configured values.
     *
     * @return A new object
     */
    public ServerObject build() {
        return new ServerObject(url, description, immutable(variables));
    }

    /**
     * Creates a builder for a {@link ServerObject}
     *
     * @return A new builder
     */
    public static ServerObjectBuilder serverObject() {
        return new ServerObjectBuilder();
    }
}