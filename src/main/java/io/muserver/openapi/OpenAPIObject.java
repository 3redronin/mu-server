package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.stream.Collectors;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see OpenAPIObjectBuilder
 */
public class OpenAPIObject implements JsonWriter {
    private final @Nullable String jsonSchemaDialect;
    private final @Nullable Map<String, ReferenceOr<PathItemObject>> webhooks;
    private final @Nullable String self;
    private final Map<String, Object> extensions;

    private final String openapi = "3.2.1";
    private final InfoObject info;
    private final @Nullable List<ServerObject> servers;
    private final @Nullable PathsObject paths;
    private final @Nullable ComponentsObject components;
    private final @Nullable List<SecurityRequirementObject> security;
    private final @Nullable List<TagObject> tags;
    private final @Nullable ExternalDocumentationObject externalDocs;

    OpenAPIObject(@Nullable InfoObject info, @Nullable List<ServerObject> servers, @Nullable PathsObject paths, @Nullable ComponentsObject components, @Nullable List<SecurityRequirementObject> security, @Nullable List<TagObject> tags, @Nullable ExternalDocumentationObject externalDocs, @Nullable String jsonSchemaDialect, @Nullable Map<String, ReferenceOr<PathItemObject>> webhooks, @Nullable String self, @Nullable Map<String, Object> extensions) {
        this.jsonSchemaDialect = jsonSchemaDialect;
        this.webhooks = OpenApiUtils.immutable(webhooks);
        this.self = self;
        this.extensions = Extensions.copy(extensions);
        if (tags != null && tags.size() != tags.stream().map(t -> t.name()).collect(Collectors.toSet()).size()) {
            throw new IllegalArgumentException("Tags must have unique names");
        }
        if (tags != null) {
            Map<String, TagObject> byName = new java.util.LinkedHashMap<>();
            for (TagObject tag : tags) byName.put(tag.name(), tag);
            for (TagObject tag : tags) {
                java.util.Set<String> visited = new java.util.HashSet<>();
                TagObject current = tag;
                while (current.parent() != null) {
                    if (!visited.add(current.name())) throw new IllegalArgumentException("Cyclic tag hierarchy at " + tag.name());
                    TagObject parent = byName.get(current.parent());
                    if (parent == null) throw new IllegalArgumentException("Unknown parent tag " + current.parent());
                    current = parent;
                }
            }
        }
        notNull("info", info);
        this.info = java.util.Objects.requireNonNull(info);
        this.servers = servers;
        if (paths == null && components == null && webhooks == null) {
            throw new IllegalArgumentException("At least one of paths, components or webhooks is required");
        }
        this.paths = paths;
        this.components = components;
        this.security = security;
        this.tags = tags;
        this.externalDocs = externalDocs;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "openapi", openapi, isFirst);
        isFirst = append(writer, "info", info, isFirst);
        isFirst = append(writer, "servers", servers, isFirst);
        isFirst = append(writer, "paths", paths, isFirst);
        isFirst = append(writer, "components", components, isFirst);
        isFirst = append(writer, "security", security, isFirst);
        isFirst = append(writer, "tags", tags, isFirst);
        isFirst = append(writer, "externalDocs", externalDocs, isFirst);
        isFirst = Jsonizer.append(writer, "jsonSchemaDialect", jsonSchemaDialect, isFirst);
        isFirst = Jsonizer.append(writer, "webhooks", webhooks, isFirst);
        isFirst = Jsonizer.append(writer, "$self", self, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write('}');
    }

    /**
     * @return The OpenAPI spec version this document conforms to
     */
    public String openApi() {
        return openapi;
    }

    /**
     * @return the value described in {@link OpenAPIObjectBuilder#withInfo}
     */
    public InfoObject info() {
        return info;
    }

    /**
      @return the value described in {@link OpenAPIObjectBuilder#withServers}
     */
    public @Nullable List<ServerObject> servers() {
        return servers;
    }

    /**
      @return the value described in {@link OpenAPIObjectBuilder#withPaths}
     */
    public @Nullable PathsObject paths() {
        return paths;
    }

    /**
      @return the value described in {@link OpenAPIObjectBuilder#withComponents}
     */
    public @Nullable ComponentsObject components() {
        return components;
    }

    /**
      @return the value described in {@link OpenAPIObjectBuilder#withSecurity}
     */
    public @Nullable List<SecurityRequirementObject> security() {
        return security;
    }

    /**
      @return the value described in {@link OpenAPIObjectBuilder#withTags}
     */
    public @Nullable List<TagObject> tags() {
        return tags;
    }

    /**
      @return the value described in {@link OpenAPIObjectBuilder#withExternalDocs}
     */
    public @Nullable ExternalDocumentationObject externalDocs() {
        return externalDocs;
    }
    /** @return the jsonSchemaDialect value */
    public @Nullable String jsonSchemaDialect() { return jsonSchemaDialect; }
    /** @return the webhooks value */
    public @Nullable Map<String, PathItemObject> webhooks() { return ReferenceValues.values(webhooks); }
    /** @return inline path items and references */
    public @Nullable Map<String, ReferenceOr<PathItemObject>> webhooksOrReferences() { return webhooks; }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public OpenAPIObjectBuilder toBuilder() {
        return new OpenAPIObjectBuilder()
            .withJsonSchemaDialect(jsonSchemaDialect).withWebhooksOrReferences(webhooks).withSelf(self).withExtensions(extensions).withInfo(info).withServers(servers).withPaths(paths).withComponents(components).withSecurity(security).withTags(tags).withExternalDocs(externalDocs);
    }
    /** @return the OpenAPI 3.2 $self value */
    public @Nullable String self() { return self; }
}
