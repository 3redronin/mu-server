package io.muserver.openapi;

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

    /**
     * Use {@link #openApi()} instead
     */
    @Deprecated
    public final String openapi = "3.0.1";
    /**
     * Use {@link #info()} instead
     */
    @Deprecated
    public final InfoObject info;
    /**
     * Use {@link #servers()} instead
     */
    @Deprecated
    public final List<ServerObject> servers;
    /**
     * Use {@link #paths()} instead
     */
    @Deprecated
    public final PathsObject paths;
    /**
     * Use {@link #components()} instead
     */
    @Deprecated
    public final ComponentsObject components;
    /**
     * Use {@link #security()} instead
     */
    @Deprecated
    public final List<SecurityRequirementObject> security;
    /**
     * Use {@link #tags()} instead
     */
    @Deprecated
    public final List<TagObject> tags;
    /**
     * Use {@link #externalDocs()} instead
     */
    @Deprecated
    public final ExternalDocumentationObject externalDocs;

    OpenAPIObject(InfoObject info, List<ServerObject> servers, PathsObject paths, ComponentsObject components, List<SecurityRequirementObject> security, List<TagObject> tags, ExternalDocumentationObject externalDocs) {
        notNull("info", info);
        notNull("paths", paths);
        if (tags != null && tags.size() != tags.stream().map(t -> t.name).collect(Collectors.toSet()).size()) {
            throw new IllegalArgumentException("Tags must have unique names");
        }
        this.info = info;
        this.servers = servers;
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
    public List<ServerObject> servers() {
        return servers;
    }

    /**
      @return the value described in {@link OpenAPIObjectBuilder#withPaths}
     */
    public PathsObject paths() {
        return paths;
    }

    /**
      @return the value described in {@link OpenAPIObjectBuilder#withComponents}
     */
    public ComponentsObject components() {
        return components;
    }

    /**
      @return the value described in {@link OpenAPIObjectBuilder#withSecurity}
     */
    public List<SecurityRequirementObject> security() {
        return security;
    }

    /**
      @return the value described in {@link OpenAPIObjectBuilder#withTags}
     */
    public List<TagObject> tags() {
        return tags;
    }

    /**
      @return the value described in {@link OpenAPIObjectBuilder#withExternalDocs}
     */
    public ExternalDocumentationObject externalDocs() {
        return externalDocs;
    }
}
