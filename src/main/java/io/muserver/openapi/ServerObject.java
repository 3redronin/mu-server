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
    private final String url;
    private final @Nullable String description;
    private final @Nullable Map<String, ServerVariableObject> variables;

    ServerObject(String url, @Nullable String description, @Nullable Map<String, ServerVariableObject> variables) {
        notNull("url", url);
        this.url = url;
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
}
