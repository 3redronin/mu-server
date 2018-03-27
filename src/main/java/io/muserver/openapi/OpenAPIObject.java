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
    public final String openapi = "3.0.1";
    public final InfoObject info;
    public final List<ServerObject> servers;
    public final PathsObject paths;
    public final ComponentsObject components;
    public final List<SecurityRequirementObject> security;
    public final List<TagObject> tags;
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

}
