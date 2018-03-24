package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import static io.muserver.openapi.Jsonizer.append;

public class PathItemObject implements JsonWriter {

    private final String summary;
    private final String description;
    private final Map<String, OperationObject> operations;
    private final List<Server> servers;
    private final List<ParameterObject> parameters;

    public PathItemObject(String summary, String description, Map<String, OperationObject> operations,
                          List<Server> servers, List<ParameterObject> parameters) {
        this.summary = summary;
        this.description = description;
        this.operations = operations;
        this.servers = servers;
        this.parameters = parameters;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = !append(writer, "summary", summary, isFirst);
        isFirst = !append(writer, "description", description, isFirst);
        if (operations != null) {
            for (String method : new String[]{"get", "put", "post", "delete", "options", "head", "patch", "trace"}) {
                isFirst = !append(writer, method, operations.get(method), isFirst);
            }
        }
        isFirst = !append(writer, "servers", servers, isFirst);
        isFirst = !append(writer, "parameters", parameters, isFirst);
        writer.write('}');
    }
}
