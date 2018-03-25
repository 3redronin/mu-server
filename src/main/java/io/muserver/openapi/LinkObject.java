package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.openapi.Jsonizer.append;

/**
 * @see LinkObjectBuilder
 */
public class LinkObject implements JsonWriter {

    public final String operationId;
    public final Map<String, Object> parameters;
    public final Object requestBody;
    public final String description;
    public final ServerObject server;

    LinkObject(String operationId, Map<String, Object> parameters, Object requestBody, String description, ServerObject server) {
        this.operationId = operationId;
        this.parameters = parameters;
        this.requestBody = requestBody;
        this.description = description;
        this.server = server;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "operationId", operationId, isFirst);
        isFirst = append(writer, "parameters", parameters, isFirst);
        isFirst = append(writer, "requestBody", requestBody, isFirst);
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "server", server, isFirst);
        writer.write('}');
    }
}
