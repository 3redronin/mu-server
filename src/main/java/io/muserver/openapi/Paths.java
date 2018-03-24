package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;

public class Paths implements JsonWriter {

    public final Map<String, PathItemObject> pathItemObjects;

    public Paths(Map<String, PathItemObject> pathItemObjects) {
        notNull("pathItemObjects", pathItemObjects);
        this.pathItemObjects = pathItemObjects;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.append('{');
        boolean isFirst = true;
        for (Map.Entry<String, PathItemObject> entry : pathItemObjects.entrySet()) {
            isFirst = Jsonizer.append(writer, entry.getKey(), entry.getValue(), isFirst);
        }
        writer.append('}');
    }
}
