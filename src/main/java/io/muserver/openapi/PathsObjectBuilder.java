package io.muserver.openapi;

import java.util.Map;

/**
 * <p>Holds the relative paths to the individual endpoints and their operations. The path is appended to the
 * URL from the {@link ServerObject} in order to construct the full URL. The Paths MAY be empty, due to
 * ACL constraints.</p>
 */
public class PathsObjectBuilder {
    private Map<String, PathItemObject> pathItemObjects;

    /**
     * @param pathItemObjects A relative path to an individual endpoint. The field name MUST begin with a slash.
     *                        The path is <strong>appended</strong> (no relative URL resolution) to the expanded
     *                        URL from the {@link ServerObject}'s <code>url</code> field in order to construct the
     *                        full URL. Path templating is allowed. When matching URLs, concrete (non-templated)
     *                        paths would be matched before their templated counterparts. Templated paths with the
     *                        same hierarchy but different templated names MUST NOT exist as they are identical.
     *                        In case of ambiguous matching, it's up to the tooling to decide which one to use.
     * @return The current builder
     */
    public PathsObjectBuilder withPathItemObjects(Map<String, PathItemObject> pathItemObjects) {
        this.pathItemObjects = pathItemObjects;
        return this;
    }

    public PathsObject build() {
        return new PathsObject(pathItemObjects);
    }

    /**
     * Creates a builder for a {@link PathsObject}
     *
     * @return A new builder
     */
    public static PathsObjectBuilder pathsObject() {
        return new PathsObjectBuilder();
    }
}