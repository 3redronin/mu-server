package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

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
    private @Nullable String operationRef;
    private @Nullable Map<String, Object> extensions;
    private @Nullable String operationId;
    private @Nullable Map<String, Object> parameters;
    private @Nullable Object requestBody;
    private @Nullable String description;
    private @Nullable ServerObject server;

    /**
     *
     * @param operationId The name of an <em>existing</em>, resolvable OAS operation, as defined with a unique <code>operationId</code>.
     *
     * @return The current builder
     */
    public LinkObjectBuilder withOperationId(@Nullable String operationId) {
        this.operationId = operationId;
        return this;
    }

    /**
     *
     * @param parameters A map representing parameters to pass to an operation as specified with <code>operationId</code>.
     *                   The key is the parameter name to be used, whereas the value can be a constant or an expression to be
     *                   evaluated and passed to the linked operation.  The parameter name can be qualified using the parameter
     *                   location <code>[{in}.]{name}</code> for operations that use the same parameter name in different locations (e.g. path.id).
     *
     * @return The current builder
     */
    public LinkObjectBuilder withParameters(@Nullable Map<String, Object> parameters) {
        this.parameters = parameters;
        return this;
    }

    /**
     *
     * @param requestBody A literal value or {expression} to use as a request body when calling the target operation.
     *
     * @return The current builder
     */
    public LinkObjectBuilder withRequestBody(@Nullable Object requestBody) {
        this.requestBody = requestBody;
        return this;
    }

    /**
     *
     * @param description A description of the link. <a href="http://spec.commonmark.org/">CommonMark syntax</a> MAY be
     *                    used for rich text representation.
     *
     * @return The current builder
     */
    public LinkObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     *
     * @param server A server object to be used by the target operation.
     *
     * @return The current builder
     */
    public LinkObjectBuilder withServer(@Nullable ServerObject server) {
        this.server = server;
        return this;
    }

    /**
     * @return A new object
     */
    public LinkObject build() {
        return new LinkObject(operationRef, operationId, immutable(parameters), requestBody, description, server, extensions);
    }

    /**
     * Creates a builder for a {@link LinkObject}
     *
     * @return A new builder
     */
    public static LinkObjectBuilder linkObject() {
        return new LinkObjectBuilder();
    }
    /**
     * @param value the extensions value
     * @return this builder */
    public LinkObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public LinkObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
    /**
     * @param value the URI reference of the target operation
     * @return this builder */
    public LinkObjectBuilder withOperationRef(@Nullable String value) { operationRef = value; return this; }
}
