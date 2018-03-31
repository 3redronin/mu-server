package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;
import static java.util.stream.Collectors.toSet;

/**
 * @see OperationObjectBuilder
 */
public class OperationObject implements JsonWriter {

    public final List<String> tags;
    public final String summary;
    public final String description;
    public final ExternalDocumentationObject externalDocs;
    public final String operationId;
    public final List<ParameterObject> parameters;
    public final RequestBodyObject requestBody;
    public final ResponsesObject responses;
    public final Map<String, CallbackObject> callbacks;
    public final boolean deprecated;
    public final List<SecurityRequirementObject> security;
    public final List<ServerObject> servers;

    OperationObject(List<String> tags, String summary, String description, ExternalDocumentationObject externalDocs,
                           String operationId, List<ParameterObject> parameters, RequestBodyObject requestBody, ResponsesObject responses,
                           Map<String, CallbackObject> callbacks, boolean deprecated, List<SecurityRequirementObject> security,
                           List<ServerObject> servers) {
        notNull("responses", responses);
        if (parameters != null) {
            Set<String> nameIns = parameters.stream().map(p -> p.name + "\0" + p.in).collect(toSet());
            if (nameIns.size() != parameters.size()) {
                throw new IllegalArgumentException("Got duplicate parameter name and locations in " + parameters + " for operation with summary " + summary);
            }
        }
        this.tags = tags;
        this.summary = summary;
        this.description = description;
        this.externalDocs = externalDocs;
        this.operationId = operationId;
        this.parameters = parameters;
        this.requestBody = requestBody;
        this.responses = responses;
        this.callbacks = callbacks;
        this.deprecated = deprecated;
        this.security = security;
        this.servers = servers;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "tags", tags, isFirst);
        isFirst = append(writer, "summary", summary, isFirst);
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "externalDocs", externalDocs, isFirst);
        isFirst = append(writer, "operationId", operationId, isFirst);
        isFirst = append(writer, "parameters", parameters, isFirst);
        isFirst = append(writer, "requestBody", requestBody, isFirst);
        isFirst = append(writer, "responses", responses, isFirst);
        isFirst = append(writer, "callbacks", callbacks, isFirst);
        isFirst = append(writer, "deprecated", deprecated, isFirst);
        isFirst = append(writer, "security", security, isFirst);
        isFirst = append(writer, "servers", servers, isFirst);
        writer.write('}');
    }
}
