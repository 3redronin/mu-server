package io.muserver.openapi;

import java.util.List;
import java.util.Map;

/**
 * Describes a single API operation on a path.
 */
public class OperationObjectBuilder {
    private List<String> tags;
    private String summary;
    private String description;
    private ExternalDocumentationObject externalDocs;
    private String operationId;
    private List<ParameterObject> parameters;
    private RequestBodyObject requestBody;
    private ResponsesObject responses;
    private Map<String, CallbackObject> callbacks;
    private boolean deprecated;
    private List<SecurityRequirementObject> security;
    private List<ServerObject> servers;

    /**
     * @param tags A list of tags for API documentation control. Tags can be used for logical grouping of operations by resources or any other qualifier.
     * @return The current builder
     */
    public OperationObjectBuilder withTags(List<String> tags) {
        this.tags = tags;
        return this;
    }

    /**
     * @param summary A short summary of what the operation does.
     * @return The current builder
     */
    public OperationObjectBuilder withSummary(String summary) {
        this.summary = summary;
        return this;
    }

    /**
     * @param description A verbose explanation of the operation behavior.
     *                    <a href="http://spec.commonmark.org/">CommonMark syntax</a> MAY be used for rich text representation.
     * @return The current builder
     */
    public OperationObjectBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * @param externalDocs Additional external documentation for this operation.
     * @return The current builder
     */
    public OperationObjectBuilder withExternalDocs(ExternalDocumentationObject externalDocs) {
        this.externalDocs = externalDocs;
        return this;
    }

    /**
     * @param operationId Unique string used to identify the operation. The id MUST be unique among all operations
     *                    described in the API. Tools and libraries MAY use the operationId to uniquely identify an
     *                    operation, therefore, it is RECOMMENDED to follow common programming naming conventions.
     * @return The current builder
     */
    public OperationObjectBuilder withOperationId(String operationId) {
        this.operationId = operationId;
        return this;
    }

    /**
     * @param parameters A list of parameters that are applicable for this operation. If a parameter is already
     *                   defined at the Path Item, the new definition will override it but can never remove it.
     *                   The list MUST NOT include duplicated parameters. A unique parameter is defined by a combination
     *                   of a name and location.
     * @return The current builder
     */
    public OperationObjectBuilder withParameters(List<ParameterObject> parameters) {
        this.parameters = parameters;
        return this;
    }

    /**
     * @param requestBody The request body applicable for this operation.  The <code>requestBody</code> is only
     *                    supported in HTTP methods where the HTTP 1.1 specification
     *                    <a href="https://tools.ietf.org/html/rfc7231#section-4.3.1">RFC7231</a> has explicitly
     *                    defined semantics for request bodies.  In other cases where the HTTP spec is vague,
     *                    <code>requestBody</code> SHALL be ignored by consumers.
     * @return The current builder
     */
    public OperationObjectBuilder withRequestBody(RequestBodyObject requestBody) {
        this.requestBody = requestBody;
        return this;
    }

    /**
     * @param responses <strong>REQUIRED</strong>. The list of possible responses as they are returned from executing this operation.
     * @return The current builder
     */
    public OperationObjectBuilder withResponses(ResponsesObject responses) {
        this.responses = responses;
        return this;
    }

    /**
     * @param callbacks A map of possible out-of band callbacks related to the parent operation. The key is a unique
     *                  identifier for the {@link CallbackObject}. Each value in the map is a Callback Object that
     *                  describes a request that may be initiated by the API provider and the expected responses.
     *                  The key value used to identify the callback object is an expression, evaluated at runtime,
     *                  that identifies a URL to use for the callback operation.
     * @return The current builder
     */
    public OperationObjectBuilder withCallbacks(Map<String, CallbackObject> callbacks) {
        this.callbacks = callbacks;
        return this;
    }

    /**
     * @param deprecated Declares this operation to be deprecated. Consumers SHOULD refrain from usage of the declared operation. Default value is <code>false</code>.
     * @return The current builder
     */
    public OperationObjectBuilder withDeprecated(boolean deprecated) {
        this.deprecated = deprecated;
        return this;
    }

    /**
     * @param security A declaration of which security mechanisms can be used for this operation. The list of values
     *                 includes alternative security requirement objects that can be used. Only one of the security
     *                 requirement objects need to be satisfied to authorize a request. This definition overrides
     *                 any declared top-level security. To remove a top-level security declaration, an empty array
     *                 can be used.
     * @return The current builder
     */
    public OperationObjectBuilder withSecurity(List<SecurityRequirementObject> security) {
        this.security = security;
        return this;
    }

    /**
     * @param servers An alternative <code>server</code> array to service this operation. If an alternative
     *                <code>server</code> object is specified at the Path Item Object or Root level, it will be
     *                overridden by this value.
     * @return The current builder
     */
    public OperationObjectBuilder withServers(List<ServerObject> servers) {
        this.servers = servers;
        return this;
    }

    public OperationObject build() {
        return new OperationObject(tags, summary, description, externalDocs, operationId, parameters, requestBody, responses, callbacks, deprecated, security, servers);
    }

    /**
     * Creates a builder for a {@link OperationObject}
     *
     * @return A new builder
     */
    public static OperationObjectBuilder operationObject() {
        return new OperationObjectBuilder();
    }
}