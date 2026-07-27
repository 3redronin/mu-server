package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.stream.Collectors;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * Represents the root OpenAPI document.
 *
 * @see OpenAPIObjectBuilder
 */
public class OpenAPIObject implements JsonWriter {

    private final String openapi = "3.0.1";
    private final InfoObject info;
    private final @Nullable List<ServerObject> servers;
    private final PathsObject paths;
    private final @Nullable ComponentsObject components;
    private final @Nullable List<SecurityRequirementObject> security;
    private final @Nullable List<TagObject> tags;
    private final @Nullable ExternalDocumentationObject externalDocs;

    OpenAPIObject(@Nullable InfoObject info, @Nullable List<ServerObject> servers, @Nullable PathsObject paths, @Nullable ComponentsObject components, @Nullable List<SecurityRequirementObject> security, @Nullable List<TagObject> tags, @Nullable ExternalDocumentationObject externalDocs) {
        if (tags != null && tags.size() != tags.stream().map(t -> t.name()).collect(Collectors.toSet()).size()) {
            throw new IllegalArgumentException("Tags must have unique names");
        }
        notNull("info", info);
        this.info = java.util.Objects.requireNonNull(info);
        this.servers = servers;
        notNull("paths", paths);
        this.paths = java.util.Objects.requireNonNull(paths);
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
        writer.write('}');
    }

    /**
     * Gets the OpenAPI specification version for this document.
     *
     * @return The OpenAPI spec version this document conforms to
     */
    public String openApi() {
        return openapi;
    }

    /**
     * Gets the API metadata section.
     *
     * @return the value described in {@link OpenAPIObjectBuilder#withInfo}
     */
    public InfoObject info() {
        return info;
    }

    /**
     * Gets the servers declared for the API.
     *
     * @return the value described in {@link OpenAPIObjectBuilder#withServers}
     */
    public @Nullable List<ServerObject> servers() {
        return servers;
    }

    /**
     * Gets the documented paths.
     *
     * @return the value described in {@link OpenAPIObjectBuilder#withPaths}
     */
    public PathsObject paths() {
        return paths;
    }

    /**
     * Gets the reusable components section.
     *
     * @return the value described in {@link OpenAPIObjectBuilder#withComponents}
     */
    public @Nullable ComponentsObject components() {
        return components;
    }

    /**
     * Gets the top-level security requirements.
     *
     * @return the value described in {@link OpenAPIObjectBuilder#withSecurity}
     */
    public @Nullable List<SecurityRequirementObject> security() {
        return security;
    }

    /**
     * Gets the top-level tags.
     *
     * @return the value described in {@link OpenAPIObjectBuilder#withTags}
     */
    public @Nullable List<TagObject> tags() {
        return tags;
    }

    /**
     * Gets the external documentation section.
     *
     * @return the value described in {@link OpenAPIObjectBuilder#withExternalDocs}
     */
    public @Nullable ExternalDocumentationObject externalDocs() {
        return externalDocs;
    }
}
