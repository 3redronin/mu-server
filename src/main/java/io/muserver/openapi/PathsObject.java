package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Maps OpenAPI path templates to their path item descriptions.
 *
 * @see PathsObjectBuilder
 */
public class PathsObject implements JsonWriter {
    private final Map<String, Object> extensions;

    private final @Nullable Map<String, PathItemObject> pathItemObjects;

    PathsObject(@Nullable Map<String, PathItemObject> pathItemObjects, @Nullable Map<String, Object> extensions) {
        this.extensions = Extensions.copy(extensions);
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
            this.pathItemObjects = Collections.unmodifiableMap(new TreeMap<>(pathItemObjects));
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
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.append('}');
    }

    /**
     * Gets the path-item mappings in this collection.
     *
     * @return the value described by {@link PathsObjectBuilder#withPathItemObjects}
     */
    public @Nullable Map<String, PathItemObject> pathItemObjects() {
        return pathItemObjects;
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public PathsObjectBuilder toBuilder() {
        return new PathsObjectBuilder()
            .withExtensions(extensions).withPathItemObjects(pathItemObjects);
    }
}
