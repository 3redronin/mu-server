package io.muserver.openapi;

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
    private final String description;
    private final Map<String, ServerVariableObject> variables;

    ServerObject(String url, String description, Map<String, ServerVariableObject> variables) {
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
    public String description() {
        return description;
    }

    /**
      @return the value described by {@link ServerObjectBuilder#withVariables}
     */
    public Map<String, ServerVariableObject> variables() {
        return variables;
    }
}
