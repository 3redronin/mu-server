package io.muserver.openapi;

import java.util.Map;

/**
 * <p>The <code>Link object</code> represents a possible design-time link for a response. The presence of a link does not
 * guarantee the caller's ability to successfully invoke it, rather it provides a known relationship and traversal mechanism
 * between responses and other operations.</p>
 * <p>Unlike <em>dynamic</em> links (i.e. links provided <strong>in</strong> the response payload), the OAS linking mechanism
 * does not require link information in the runtime response.</p>
 * <p>For computing links, and providing instructions to execute them, a runtime expression is used for accessing values in
 * an operation and using them as parameters while invoking the linked operation.</p>
 */
public class LinkObjectBuilder {
    private String operationId;
    private Map<String, Object> parameters;
    private Object requestBody;
    private String description;
    private ServerObject server;

    /**
     * @param operationId The name of an <em>existing</em>, resolvable OAS operation, as defined with a unique <code>operationId</code>.
     * @return The current builder
     */
    public LinkObjectBuilder withOperationId(String operationId) {
        this.operationId = operationId;
        return this;
    }

    /**
     * @param parameters A map representing parameters to pass to an operation as specified with <code>operationId</code>.
     *                   The key is the parameter name to be used, whereas the value can be a constant or an expression to be
     *                   evaluated and passed to the linked operation.  The parameter name can be qualified using the parameter
     *                   location <code>[{in}.]{name}</code> for operations that use the same parameter name in different locations (e.g. path.id).
     * @return The current builder
     */
    public LinkObjectBuilder withParameters(Map<String, Object> parameters) {
        this.parameters = parameters;
        return this;
    }

    /**
     * @param requestBody A literal value or {expression} to use as a request body when calling the target operation.
     * @return The current builder
     */
    public LinkObjectBuilder withRequestBody(Object requestBody) {
        this.requestBody = requestBody;
        return this;
    }

    /**
     * @param description A description of the link. <a href="http://spec.commonmark.org/">CommonMark syntax</a> MAY be
     *                    used for rich text representation.
     * @return The current builder
     */
    public LinkObjectBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    /**
     * @param server A server object to be used by the target operation.
     * @return The current builder
     */
    public LinkObjectBuilder withServer(ServerObject server) {
        this.server = server;
        return this;
    }

    public LinkObject build() {
        return new LinkObject(operationId, parameters, requestBody, description, server);
    }

    /**
     * Creates a builder for a {@link LinkObject}
     *
     * @return A new builder
     */
    public static LinkObjectBuilder linkObject() {
        return new LinkObjectBuilder();
    }
}