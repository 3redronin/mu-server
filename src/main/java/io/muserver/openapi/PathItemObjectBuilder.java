package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * Describes the operations available on a single path. A Path Item MAY be empty, due to ACL constraints.
 * The path itself is still exposed to the documentation viewer but they will not know which operations
 * and parameters are available.
 */
public class PathItemObjectBuilder {
    private @Nullable String summary;
    private @Nullable String description;
    private @Nullable Map<String, OperationObject> operations;
    private @Nullable List<ServerObject> servers;
    private @Nullable List<ParameterObject> parameters;

    /**
     * @param summary An optional, string summary, intended to apply to all operations in this path.
     * @return The current builder
     */
    public PathItemObjectBuilder withSummary(@Nullable String summary) {
        this.summary = summary;
        return this;
    }

    /**
     * @param description An optional, string description, intended to apply to all operations in this path.
     *                    <a href="http://spec.commonmark.org/">CommonMark syntax</a> MAY be used for rich text representation.
     * @return The current builder
     */
    public PathItemObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     * @param operations The operations allowed on this path, where the keys to the map are <code>GET</code>, <code>POST</code> etc.
     * @return The current builder
     */
    public PathItemObjectBuilder withOperations(@Nullable Map<String, OperationObject> operations) {
        this.operations = operations;
        return this;
    }

    /**
     * @param servers An alternative server array to service all operations in this path.
     * @return The current builder
     */
    public PathItemObjectBuilder withServers(@Nullable List<ServerObject> servers) {
        this.servers = servers;
        return this;
    }

    /**
     * @param parameters A list of parameters that are applicable for all the operations described under this path.
     *                   These parameters can be overridden at the operation level, but cannot be removed there. The
     *                   list MUST NOT include duplicated parameters. A unique parameter is defined by a combination
     *                   of a name and location.
     * @return The current builder
     */
    public PathItemObjectBuilder withParameters(@Nullable List<ParameterObject> parameters) {
        this.parameters = parameters;
        return this;
    }

    /**
     * @return A new object
     */
    public PathItemObject build() {
        return new PathItemObject(summary, description, immutable(operations), immutable(servers), immutable(parameters));
    }

    /**
     * Creates a builder for a {@link PathItemObject}
     *
     * @return A new builder
     */
    public static PathItemObjectBuilder pathItemObject() {
        return new PathItemObjectBuilder();
    }

    @Nullable String summary() {
        return summary;
    }

    @Nullable String description() {
        return description;
    }

    /**
     * @return The value set with {@link #withOperations}
     */
    public @Nullable Map<String, OperationObject> operations() {
        return operations;
    }

    @Nullable List<ServerObject> servers() {
        return servers;
    }

    @Nullable List<ParameterObject> parameters() {
        return parameters;
    }
}