package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * Describes a single API operation on a path.
 */
public class OperationObjectBuilder {
    private @Nullable List<String> tags;
    private @Nullable String summary;
    private @Nullable String description;
    private @Nullable ExternalDocumentationObject externalDocs;
    private @Nullable String operationId;
    private @Nullable List<ParameterObject> parameters;
    private @Nullable RequestBodyObject requestBody;
    private @Nullable ResponsesObject responses;
    private @Nullable Map<String, CallbackObject> callbacks;
    private @Nullable Boolean deprecated;
    private @Nullable List<SecurityRequirementObject> security;
    private @Nullable List<ServerObject> servers;

    /**
     * Creates an empty operation object builder.
     */
    public OperationObjectBuilder() {
    }

    /**
     * Sets the tags for the operation.
     *
     * @param tags A list of tags for API documentation control. Tags can be used for logical grouping of operations by resources or any other qualifier.
     * @return The current builder
     */
    public OperationObjectBuilder withTags(@Nullable List<String> tags) {
        this.tags = tags;
        return this;
    }

    /**
     * Sets the short summary for the operation.
     *
     * @param summary A short summary of what the operation does.
     * @return The current builder
     */
    public OperationObjectBuilder withSummary(@Nullable String summary) {
        this.summary = summary;
        return this;
    }

    /**
     * Sets the detailed description for the operation.
     *
     * @param description A verbose explanation of the operation behavior.
     *                    <a href="http://spec.commonmark.org/">CommonMark syntax</a> MAY be used for rich text representation.
     * @return The current builder
     */
    public OperationObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     * Sets the external documentation for the operation.
     *
     * @param externalDocs Additional external documentation for this operation.
     * @return The current builder
     */
    public OperationObjectBuilder withExternalDocs(@Nullable ExternalDocumentationObject externalDocs) {
        this.externalDocs = externalDocs;
        return this;
    }

    /**
     * Sets the unique operation identifier.
     *
     * @param operationId Unique string used to identify the operation. The id MUST be unique among all operations
     *                    described in the API. Tools and libraries MAY use the operationId to uniquely identify an
     *                    operation, therefore, it is RECOMMENDED to follow common programming naming conventions.
     * @return The current builder
     */
    public OperationObjectBuilder withOperationId(@Nullable String operationId) {
        this.operationId = operationId;
        return this;
    }

    /**
     * Sets the parameters accepted by the operation.
     *
     * @param parameters A list of parameters that are applicable for this operation. If a parameter is already
     *                   defined at the Path Item, the new definition will override it but can never remove it.
     *                   The list MUST NOT include duplicated parameters. A unique parameter is defined by a combination
     *                   of a name and location.
     * @return The current builder
     */
    public OperationObjectBuilder withParameters(@Nullable List<ParameterObject> parameters) {
        this.parameters = parameters;
        return this;
    }

    /**
     * Sets the request body description for the operation.
     *
     * @param requestBody The request body applicable for this operation.  The <code>requestBody</code> is only
     *                    supported in HTTP methods where the HTTP 1.1 specification
     *                    <a href="https://tools.ietf.org/html/rfc7231#section-4.3.1">RFC7231</a> has explicitly
     *                    defined semantics for request bodies.  In other cases where the HTTP spec is vague,
     *                    <code>requestBody</code> SHALL be ignored by consumers.
     * @return The current builder
     */
    public OperationObjectBuilder withRequestBody(@Nullable RequestBodyObject requestBody) {
        this.requestBody = requestBody;
        return this;
    }

    /**
     * Sets the response definitions for the operation.
     *
     * @param responses <strong>REQUIRED</strong>. The list of possible responses as they are returned from executing this operation.
     * @return The current builder
     */
    public OperationObjectBuilder withResponses(ResponsesObject responses) {
        this.responses = responses;
        return this;
    }

    /**
     * Sets the callbacks associated with the operation.
     *
     * @param callbacks A map of possible out-of band callbacks related to the parent operation. The key is a unique
     *                  identifier for the {@link CallbackObject}. Each value in the map is a Callback Object that
     *                  describes a request that may be initiated by the API provider and the expected responses.
     *                  The key value used to identify the callback object is an expression, evaluated at runtime,
     *                  that identifies a URL to use for the callback operation.
     * @return The current builder
     */
    public OperationObjectBuilder withCallbacks(@Nullable Map<String, CallbackObject> callbacks) {
        this.callbacks = callbacks;
        return this;
    }

    /**
     * Sets whether the operation is deprecated.
     *
     * @param deprecated Declares this operation to be deprecated. Consumers SHOULD refrain from usage of the declared operation. Default value is <code>false</code>.
     * @return The current builder
     */
    public OperationObjectBuilder withDeprecated(@Nullable Boolean deprecated) {
        this.deprecated = deprecated;
        return this;
    }

    /**
     * Sets the security requirements for the operation.
     *
     * @param security A declaration of which security mechanisms can be used for this operation. The list of values
     *                 includes alternative security requirement objects that can be used. Only one of the security
     *                 requirement objects need to be satisfied to authorize a request. This definition overrides
     *                 any declared top-level security. To remove a top-level security declaration, an empty array
     *                 can be used.
     * @return The current builder
     */
    public OperationObjectBuilder withSecurity(@Nullable List<SecurityRequirementObject> security) {
        this.security = security;
        return this;
    }

    /**
     * Sets the servers that can service the operation.
     *
     * @param servers An alternative <code>server</code> array to service this operation. If an alternative
     *                <code>server</code> object is specified at the Path Item Object or Root level, it will be
     *                overridden by this value.
     * @return The current builder
     */
    public OperationObjectBuilder withServers(@Nullable List<ServerObject> servers) {
        this.servers = servers;
        return this;
    }

    /**
     * Builds an operation object from the configured values.
     *
     * @return A new object
     */
    public OperationObject build() {
        return new OperationObject(immutable(tags), summary, description, externalDocs, operationId, immutable(parameters),
            requestBody, responses, immutable(callbacks), deprecated, immutable(security), immutable(servers));
    }

    /**
     * Creates a builder for a {@link OperationObject}
     *
     * @return A new builder
     */
    public static OperationObjectBuilder operationObject() {
        return new OperationObjectBuilder();
    }

    /**
     * Creates a builder from the given operation object
     * @param operation The object to copy values from
     * @return An operation object builder
     */
    public static OperationObjectBuilder builderFrom(OperationObject operation) {
        return new OperationObjectBuilder()
            .withTags(operation.tags())
            .withSummary(operation.summary())
            .withDescription(operation.description())
            .withExternalDocs(operation.externalDocs())
            .withOperationId(operation.operationId())
            .withParameters(operation.parameters())
            .withRequestBody(operation.requestBody())
            .withResponses(operation.responses())
            .withCallbacks(operation.callbacks())
            .withDeprecated(operation.deprecated())
            .withSecurity(operation.security())
            .withServers(operation.servers())
            ;
    }
}