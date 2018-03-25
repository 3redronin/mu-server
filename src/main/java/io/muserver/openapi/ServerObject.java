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
    public final String url;
    public final String description;
    public final Map<String, ServerVariableObject> variables;

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
}
