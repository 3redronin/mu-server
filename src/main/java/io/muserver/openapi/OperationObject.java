package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

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

    private final @Nullable List<String> tags;
    private final @Nullable String summary;
    private final @Nullable String description;
    private final @Nullable ExternalDocumentationObject externalDocs;
    private final @Nullable String operationId;
    private final @Nullable List<ParameterObject> parameters;
    private final @Nullable RequestBodyObject requestBody;
    private final ResponsesObject responses;
    private final @Nullable Map<String, CallbackObject> callbacks;
    private final @Nullable Boolean deprecated;
    private final @Nullable List<SecurityRequirementObject> security;
    private final @Nullable List<ServerObject> servers;

    OperationObject(@Nullable List<String> tags, @Nullable String summary, @Nullable String description, @Nullable ExternalDocumentationObject externalDocs,
                           @Nullable String operationId, @Nullable List<ParameterObject> parameters, @Nullable RequestBodyObject requestBody, ResponsesObject responses,
                           @Nullable Map<String, CallbackObject> callbacks, @Nullable Boolean deprecated, @Nullable List<SecurityRequirementObject> security,
                           @Nullable List<ServerObject> servers) {
        notNull("responses", responses);
        if (parameters != null) {
            Set<String> nameIns = parameters.stream().map(p -> p.name() + "\0" + p.in()).collect(toSet());
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

    /**
     * @return the value described by {@link OperationObjectBuilder#withDeprecated(Boolean)}, or false if not specified when building
     */
    public boolean isDeprecated() {
        return deprecated != null && deprecated;
    }

    /**
     * @return the value described by {@link OperationObjectBuilder#withTags}
     */
    public @Nullable List<String> tags() {
        return tags;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withSummary}
     */
    public @Nullable String summary() {
        return summary;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withExternalDocs}
     */
    public @Nullable ExternalDocumentationObject externalDocs() {
        return externalDocs;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withOperationId}
     */
    public @Nullable String operationId() {
        return operationId;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withParameters}
     */
    public @Nullable List<ParameterObject> parameters() {
        return parameters;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withRequestBody}
     */
    public @Nullable RequestBodyObject requestBody() {
        return requestBody;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withResponses}
     */
    public ResponsesObject responses() {
        return responses;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withCallbacks}
     */
    public @Nullable Map<String, CallbackObject> callbacks() {
        return callbacks;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withDeprecated}
     */
    public @Nullable Boolean deprecated() {
        return deprecated;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withSecurity}
     */
    public @Nullable List<SecurityRequirementObject> security() {
        return security;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withServers}
     */
    public @Nullable List<ServerObject> servers() {
        return servers;
    }
}
