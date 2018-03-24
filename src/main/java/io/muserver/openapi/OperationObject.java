package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

public class OperationObject implements JsonWriter {

    private final List<String> tags;
    private final String summary;
    private final String description;
    private final ExternalDocumentationObject externalDocs;
    private final String operationId;
    private final List<ParameterObject> parameters;
    private final RequestBodyObject requestBody;
    private final ResponsesObject responses;
    private final Map<String, CallbackObject> callbacks;
    private final boolean deprecated;
    private final List<SecurityRequirementObject> security;
    private final List<Server> servers;

    public OperationObject(List<String> tags, String summary, String description, ExternalDocumentationObject externalDocs,
                           String operationId, List<ParameterObject> parameters, RequestBodyObject requestBody, ResponsesObject responses,
                           Map<String, CallbackObject> callbacks, boolean deprecated, List<SecurityRequirementObject> security,
                           List<Server> servers) {
        notNull("responses", responses);
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
        writer.write("{");
        boolean isFirst = true;
        isFirst = !append(writer, "tags", tags, isFirst);
        isFirst = !append(writer, "summary", summary, isFirst);
        isFirst = !append(writer, "description", description, isFirst);
        isFirst = !append(writer, "externalDocs", externalDocs, isFirst);
        isFirst = !append(writer, "operationId", operationId, isFirst);
        isFirst = !append(writer, "parameters", parameters, isFirst);
        isFirst = !append(writer, "requestBody", requestBody, isFirst);
        isFirst = !append(writer, "responses", responses, isFirst);
        isFirst = !append(writer, "callbacks", callbacks, isFirst);
        isFirst = !append(writer, "deprecated", deprecated, isFirst);
        isFirst = !append(writer, "security", security, isFirst);
        isFirst = !append(writer, "servers", servers, isFirst);
        writer.write("}");
    }
}
