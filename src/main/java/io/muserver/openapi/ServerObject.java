package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see ServerObjectBuilder
 */
public class ServerObject implements JsonWriter {
    private final @Nullable String name;
    private final Map<String, Object> extensions;
    private final String url;
    private final @Nullable String description;
    private final @Nullable Map<String, ServerVariableObject> variables;

    ServerObject(@Nullable String url, @Nullable String description, @Nullable Map<String, ServerVariableObject> variables, @Nullable String name, @Nullable Map<String, Object> extensions) {
        this.name = name;
        this.extensions = Extensions.copy(extensions);
        notNull("url", url);
        this.url = java.util.Objects.requireNonNull(url);
        this.description = description;
        this.variables = variables;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write("{");
        boolean isFirst = true;
        isFirst = append(writer, "url", url, isFirst);
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "variables", variables, isFirst);
        isFirst = Jsonizer.append(writer, "name", name, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write("}");
    }

    /**
     * @return the value described by {@link ServerObjectBuilder#withUrl}
     */
    public String url() {
        return url;
    }

    /**
      @return the value described by {@link ServerObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
      @return the value described by {@link ServerObjectBuilder#withVariables}
     */
    public @Nullable Map<String, ServerVariableObject> variables() {
        return variables;
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public ServerObjectBuilder toBuilder() {
        return new ServerObjectBuilder()
            .withName(name).withExtensions(extensions).withUrl(url).withDescription(description).withVariables(variables);
    }
    /** @return the OpenAPI 3.2 name value */
    public @Nullable String name() { return name; }
}
