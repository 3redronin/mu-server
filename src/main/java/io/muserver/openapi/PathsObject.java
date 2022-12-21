package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @see PathsObjectBuilder
 */
public class PathsObject implements JsonWriter {

    /**
     * @deprecated use {@link #pathItemObjects()} instead
     */
    @Deprecated
    public final Map<String, PathItemObject> pathItemObjects;

    PathsObject(Map<String, PathItemObject> pathItemObjects) {
        if (pathItemObjects != null) {
            for (String path : pathItemObjects.keySet()) {
                if (!path.startsWith("/")) {
                    throw new IllegalArgumentException("Each path must start with a '/' but got '" + path + "' from " + pathItemObjects);
                }
            }
            Set<String> ids = new HashSet<>();
            for (PathItemObject pathItemObject : pathItemObjects.values()) {
                if (pathItemObject.operations() != null) {
                    for (OperationObject oo : pathItemObject.operations().values()) {
                        if (oo.operationId() != null) {
                            if (ids.contains(oo.operationId())) {
                                throw new IllegalArgumentException("Cannot have duplicate operation IDs, but got " + oo.operationId());
                            }
                            ids.add(oo.operationId());
                        }
                    }
                }
            }
            this.pathItemObjects = new LinkedHashMap<>(pathItemObjects.size());
            for (Map.Entry<String, PathItemObject> entry : pathItemObjects.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .collect(Collectors.toList())
            ) {
                this.pathItemObjects.put(entry.getKey(), entry.getValue());
            }
        } else {
            this.pathItemObjects = null;
        }
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.append('{');
        boolean isFirst = true;
        if (pathItemObjects != null) {
            for (Map.Entry<String, PathItemObject> entry : pathItemObjects.entrySet()) {
                isFirst = Jsonizer.append(writer, entry.getKey(), entry.getValue(), isFirst);
            }
        }
        writer.append('}');
    }

    /**
     * @return the value described by {@link PathsObjectBuilder#withPathItemObjects}
     */
    public Map<String, PathItemObject> pathItemObjects() {
        return pathItemObjects;
    }
}
