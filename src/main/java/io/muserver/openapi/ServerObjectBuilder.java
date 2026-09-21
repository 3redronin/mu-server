package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * An object representing a Server.
 */
public class ServerObjectBuilder {
    private @Nullable String name;
    private @Nullable Map<String, Object> extensions;
    private @Nullable String url;
    private @Nullable String description;
    private @Nullable Map<String, ServerVariableObject> variables;

    /**
     *
     * @param url <strong>REQUIRED</strong>. A URL to the target host.  This URL supports Server Variables and MAY be relative, to indicate
     * that the host location is relative to the location where the OpenAPI document is being served. Variable substitutions will
     * be made when a variable is named in <code>{</code>brackets<code>}</code>.
     *
     * @return The current builder
     */
    public ServerObjectBuilder withUrl(String url) {
        this.url = url;
        return this;
    }

    /**
     *
     * @param description An optional string describing the host designated by the URL. CommonMark syntax MAY be used for rich text representation.
     *
     * @return The current builder
     */
    public ServerObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     *
     * @param variables A map between a variable name and its value. The value is used for substitution in the server's URL template.
     *
     * @return The current builder
     */
    public ServerObjectBuilder withVariables(@Nullable Map<String, ServerVariableObject> variables) {
        this.variables = variables;
        return this;
    }

    /**
     * @return A new object
     */
    public ServerObject build() {
        return new ServerObject(url, description, immutable(variables), name, extensions);
    }

    /**
     * Creates a builder for a {@link ServerObject}
     *
     * @return A new builder
     */
    public static ServerObjectBuilder serverObject() {
        return new ServerObjectBuilder();
    }
    /**
     * @param value the extensions value
     * @return this builder */
    public ServerObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public ServerObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
    /**
     * @param value the OpenAPI 3.2 name value
     * @return this builder
     */
    public ServerObjectBuilder withName(@Nullable String value) { this.name = value; return this; }
}
