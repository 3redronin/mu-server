package io.muserver.openapi;

import java.util.Map;

/**
 * An object representing a Server.
 */
public class ServerObjectBuilder {
    private String url;
    private String description;
    private Map<String, ServerVariableObject> variables;

    /**
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
     * @param description An optional string describing the host designated by the URL. CommonMark syntax MAY be used for rich text representation.
     * @return The current builder
     */
    public ServerObjectBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * @param variables A map between a variable name and its value. The value is used for substitution in the server's URL template.
     * @return The current builder
     */
    public ServerObjectBuilder withVariables(Map<String, ServerVariableObject> variables) {
        this.variables = variables;
        return this;
    }

    public ServerObject build() {
        return new ServerObject(url, description, variables);
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