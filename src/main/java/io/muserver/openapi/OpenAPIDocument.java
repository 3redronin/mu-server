package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

import static io.muserver.Mutils.notNull;

public class OpenAPIDocument implements JsonWriter {
    public final String openapi = "3.0.1";
    public final Info info;
    public final List<Server> servers;

    public OpenAPIDocument(Info info, List<Server> servers) {
        notNull("info", info);
        notNull("servers", servers);
        this.info = info;
        this.servers = servers;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = !Jsonizer.append(writer, "openapi", openapi, isFirst);
        isFirst = !Jsonizer.append(writer, "info", info, isFirst);
        isFirst = !Jsonizer.append(writer, "servers", servers, isFirst);
        writer.write('}');
    }

}
