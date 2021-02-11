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

    /**
     * @deprecated use {@link #tags()} instead
     */
    @Deprecated
    public final List<String> tags;
    /**
      @deprecated use {@link #summary()} instead
     */
    @Deprecated
    public final String summary;
    /**
      @deprecated use {@link #description()} instead
     */
    @Deprecated
    public final String description;
    /**
      @deprecated use {@link #externalDocs()} instead
     */
    @Deprecated
    public final ExternalDocumentationObject externalDocs;
    /**
      @deprecated use {@link #operationId()} instead
     */
    @Deprecated
    public final String operationId;
    /**
      @deprecated use {@link #parameters()} instead
     */
    @Deprecated
    public final List<ParameterObject> parameters;
    /**
      @deprecated use {@link #requestBody()} instead
     */
    @Deprecated
    public final RequestBodyObject requestBody;
    /**
      @deprecated use {@link #responses()} instead
     */
    @Deprecated
    public final ResponsesObject responses;
    /**
      @deprecated use {@link #callbacks()} instead
     */
    @Deprecated
    public final Map<String, CallbackObject> callbacks;
    /**
      @deprecated use {@link #deprecated()} instead
     */
    @Deprecated
    public final Boolean deprecated;
    /**
      @deprecated use {@link #security()} instead
     */
    @Deprecated
    public final List<SecurityRequirementObject> security;
    /**
      @deprecated use {@link #servers()} instead
     */
    @Deprecated
    public final List<ServerObject> servers;

    OperationObject(List<String> tags, String summary, String description, ExternalDocumentationObject externalDocs,
                           String operationId, List<ParameterObject> parameters, RequestBodyObject requestBody, ResponsesObject responses,
                           Map<String, CallbackObject> callbacks, Boolean deprecated, List<SecurityRequirementObject> security,
                           List<ServerObject> servers) {
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

    public boolean isDeprecated() {
        return deprecated != null && deprecated;
    }

    /**
     * @return the value described by {@link OperationObjectBuilder#withTags}
     */
    public List<String> tags() {
        return tags;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withSummary}
     */
    public String summary() {
        return summary;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withDescription}
     */
    public String description() {
        return description;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withExternalDocs}
     */
    public ExternalDocumentationObject externalDocs() {
        return externalDocs;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withOperationId}
     */
    public String operationId() {
        return operationId;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withParameters}
     */
    public List<ParameterObject> parameters() {
        return parameters;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withRequestBody}
     */
    public RequestBodyObject requestBody() {
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
    public Map<String, CallbackObject> callbacks() {
        return callbacks;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withDeprecated}
     */
    public Boolean deprecated() {
        return deprecated;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withSecurity}
     */
    public List<SecurityRequirementObject> security() {
        return security;
    }

    /**
      @return the value described by {@link OperationObjectBuilder#withServers}
     */
    public List<ServerObject> servers() {
        return servers;
    }
}
