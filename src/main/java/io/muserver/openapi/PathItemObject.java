package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.muserver.openapi.Jsonizer.append;
import static java.util.stream.Collectors.toSet;

/**
 * @see PathItemObjectBuilder
 */
public class PathItemObject implements JsonWriter {

    private final @Nullable String summary;
    private final @Nullable String description;
    private final @Nullable Map<String, OperationObject> operations;
    private final @Nullable List<ServerObject> servers;
    private final @Nullable List<ParameterObject> parameters;

    PathItemObject(@Nullable String summary, @Nullable String description, @Nullable Map<String, OperationObject> operations,
                          @Nullable List<ServerObject> servers, @Nullable List<ParameterObject> parameters) {
        if (parameters != null) {
            Set<String> nameIns = parameters.stream().map(p -> p.name() + "\0" + p.in()).collect(toSet());
            if (nameIns.size() != parameters.size()) {
                throw new IllegalArgumentException("Got duplicate parameter name and locations in " + parameters);
            }
        }
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
        isFirst = append(writer, "summary", summary, isFirst);
        isFirst = append(writer, "description", description, isFirst);
        if (operations != null) {
            for (String method : new String[]{"get", "put", "post", "delete", "options", "head", "patch", "trace"}) {
                isFirst = append(writer, method, operations.get(method), isFirst);
            }
        }
        isFirst = append(writer, "servers", servers, isFirst);
        isFirst = append(writer, "parameters", parameters, isFirst);
        writer.write('}');
    }

    /**
     * @return the value described by {@link PathItemObjectBuilder#withSummary}
     */
    public @Nullable String summary() {
        return summary;
    }

    /**
      @return the value described by {@link PathItemObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
      @return the value described by {@link PathItemObjectBuilder#withOperations}
     */
    public @Nullable Map<String, OperationObject> operations() {
        return operations;
    }

    /**
      @return the value described by {@link PathItemObjectBuilder#withServers}
     */
    public @Nullable List<ServerObject> servers() {
        return servers;
    }

    /**
      @return the value described by {@link PathItemObjectBuilder#withParameters}
     */
    public @Nullable List<ParameterObject> parameters() {
        return parameters;
    }
}
