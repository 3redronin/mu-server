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
    private final @Nullable String ref;
    private final @Nullable Map<String, OperationObject> additionalOperations;
    private final Map<String, Object> extensions;

    private final @Nullable String summary;
    private final @Nullable String description;
    private final @Nullable Map<String, OperationObject> operations;
    private final @Nullable List<ServerObject> servers;
    private final @Nullable List<ReferenceOr<ParameterObject>> parameters;

    PathItemObject(@Nullable String summary, @Nullable String description, @Nullable Map<String, OperationObject> operations,
                          @Nullable List<ServerObject> servers, @Nullable List<ReferenceOr<ParameterObject>> parameters, @Nullable String ref, @Nullable Map<String, OperationObject> additionalOperations, @Nullable Map<String, Object> extensions) {
        if (additionalOperations != null) {
            for (String method : additionalOperations.keySet()) {
                if (!method.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || java.util.Arrays.asList("GET", "PUT", "POST", "DELETE", "OPTIONS", "HEAD", "PATCH", "TRACE", "QUERY").contains(method)) {
                    throw new IllegalArgumentException("Invalid additional HTTP operation: " + method);
                }
            }
        }
        ParameterObject.validateLocations(parameters);
        java.util.List<OperationObject> allOperations = new java.util.ArrayList<>();
        if (operations != null) allOperations.addAll(operations.values());
        if (additionalOperations != null) allOperations.addAll(additionalOperations.values());
        for (OperationObject operation : allOperations) {
            Map<String, ReferenceOr<ParameterObject>> combined = new java.util.LinkedHashMap<>();
            for (List<ReferenceOr<ParameterObject>> level : java.util.Arrays.asList(parameters, operation.parametersOrReferences())) {
                if (level != null) for (ReferenceOr<ParameterObject> p : level) {
                    String key = p.isReference() ? java.util.Objects.requireNonNull(p.reference()).ref() : p.value().in() + "\0" + p.value().name();
                    combined.put(key, p);
                }
            }
            ParameterObject.validateLocations(new java.util.ArrayList<>(combined.values()));
        }
        this.ref = ref;
        this.additionalOperations = OpenApiUtils.immutable(additionalOperations);
        this.extensions = Extensions.copy(extensions);
        if (parameters != null) {
            Set<String> nameIns = parameters.stream().map(p -> p.isReference() ? "ref:" + java.util.Objects.requireNonNull(p.reference()).ref() : p.value().name() + "\0" + p.value().in()).collect(toSet());
            if (nameIns.size() != parameters.size()) {
                throw new IllegalArgumentException("Got duplicate parameter name and locations in " + parameters);
            }
        }
        this.summary = summary;
        this.description = description;
        java.util.Map<String, OperationObject> normalized = null;
        if (operations != null) {
            normalized = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, OperationObject> operation : operations.entrySet()) {
                String method = operation.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!java.util.Arrays.asList("get", "put", "post", "delete", "options", "head", "patch", "trace", "query").contains(method)) {
                    throw new IllegalArgumentException("Unsupported OpenAPI method: " + operation.getKey());
                }
                if (normalized.put(method, operation.getValue()) != null) throw new IllegalArgumentException("Duplicate operation: " + method);
            }
        }
        this.operations = OpenApiUtils.immutable(normalized);
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
            for (String method : new String[]{"get", "put", "post", "delete", "options", "head", "patch", "trace", "query"}) {
                isFirst = append(writer, method, operations.get(method), isFirst);
            }
        }
        isFirst = append(writer, "servers", servers, isFirst);
        isFirst = append(writer, "parameters", parameters, isFirst);
        isFirst = Jsonizer.append(writer, "$ref", ref, isFirst);
        isFirst = Jsonizer.append(writer, "additionalOperations", additionalOperations, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
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
        return ReferenceValues.values(parameters);
    }
    /** @return the ref value */
    public @Nullable String ref() { return ref; }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return inline values and references for parameters */
    public @Nullable List<ReferenceOr<ParameterObject>> parametersOrReferences() { return parameters; }
    /** @return a builder preserving all fields and extensions */
    public PathItemObjectBuilder toBuilder() {
        return new PathItemObjectBuilder()
            .withRef(ref).withAdditionalOperations(additionalOperations).withExtensions(extensions).withSummary(summary).withDescription(description).withOperations(operations).withServers(servers).withParametersOrReferences(parameters);
    }
    /** @return the OpenAPI 3.2 additionalOperations value */
    public @Nullable Map<String, OperationObject> additionalOperations() { return additionalOperations; }
}
