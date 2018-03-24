package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

import static io.muserver.Mutils.notNull;

public class OpenAPIDocument implements JsonWriter {
    public final String openapi = "3.0.1";
    public final Info info;
    public final List<Server> servers;
    public final Paths paths;
    public final Components components;
    public final List<SecurityRequirementObject> security;
    public final List<TagObject> tags;
    public final ExternalDocumentationObject externalDocs;

    public OpenAPIDocument(Info info, List<Server> servers, Paths paths, Components components, List<SecurityRequirementObject> security, List<TagObject> tags, ExternalDocumentationObject externalDocs) {
        this.components = components;
        this.security = security;
        this.tags = tags;
        this.externalDocs = externalDocs;
        notNull("info", info);
        notNull("servers", servers);
        notNull("paths", paths);
        this.info = info;
        this.servers = servers;
        this.paths = paths;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = !Jsonizer.append(writer, "openapi", openapi, isFirst);
        isFirst = !Jsonizer.append(writer, "info", info, isFirst);
        isFirst = !Jsonizer.append(writer, "servers", servers, isFirst);
        isFirst = !Jsonizer.append(writer, "paths", paths, isFirst);
        writer.write('}');
    }

}
