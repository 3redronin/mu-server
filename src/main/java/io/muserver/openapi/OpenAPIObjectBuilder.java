package io.muserver.openapi;

import java.util.List;

import static io.muserver.openapi.InfoObjectBuilder.infoObject;

/**
 * This is the root document object of the OpenAPI document.
 */
public class OpenAPIObjectBuilder {
    private InfoObject info;
    private List<ServerObject> servers;
    private PathsObject paths;
    private ComponentsObject components;
    private List<SecurityRequirementObject> security;
    private List<TagObject> tags;
    private ExternalDocumentationObject externalDocs;

    /**
     * @param info <strong>REQUIRED</strong>. Provides metadata about the API. The metadata MAY be used by tooling as required.
     * @return The current builder
     */
    public OpenAPIObjectBuilder withInfo(InfoObject info) {
        this.info = info;
        return this;
    }

    /**
     * @param servers An array of Server Objects, which provide connectivity information to a target server. If the <code>servers</code>
     *                property is not provided, or is an empty array, the default value would be a {@link ServerObject} with a
     *                <code>url</code> value of <code>/</code>.
     * @return The current builder
     */
    public OpenAPIObjectBuilder withServers(List<ServerObject> servers) {
        this.servers = servers;
        return this;
    }

    /**
     * @param paths <strong>REQUIRED</strong>. The available paths and operations for the API.
     * @return The current builder
     */
    public OpenAPIObjectBuilder withPaths(PathsObject paths) {
        this.paths = paths;
        return this;
    }

    /**
     * @param components An element to hold various schemas for the specification.
     * @return The current builder
     */
    public OpenAPIObjectBuilder withComponents(ComponentsObject components) {
        this.components = components;
        return this;
    }

    /**
     * @param security A declaration of which security mechanisms can be used across the API. The list of values includes
     *                 alternative security requirement objects that can be used. Only one of the security requirement
     *                 objects need to be satisfied to authorize a request. Individual operations can override this definition.
     * @return The current builder
     */
    public OpenAPIObjectBuilder withSecurity(List<SecurityRequirementObject> security) {
        this.security = security;
        return this;
    }

    /**
     * @param tags A list of tags used by the specification with additional metadata. The order of the tags can be used
     *             to reflect on their order by the parsing tools. Not all tags that are used by the {@link OperationObject} must
     *             be declared. The tags that are not declared MAY be organized randomly or based on the tools' logic.
     *             Each tag name in the list MUST be unique.
     * @return The current builder
     */
    public OpenAPIObjectBuilder withTags(List<TagObject> tags) {
        this.tags = tags;
        return this;
    }

    /**
     * @param externalDocs Additional external documentation.
     * @return The current builder
     */
    public OpenAPIObjectBuilder withExternalDocs(ExternalDocumentationObject externalDocs) {
        this.externalDocs = externalDocs;
        return this;
    }

    public OpenAPIObject build() {
        InfoObject infoToUse = this.info == null ? infoObject().build() : this.info;
        return new OpenAPIObject(infoToUse, servers, paths, components, security, tags, externalDocs);
    }

    /**
     * Creates a builder for an {@link OpenAPIObject}
     *
     * @return A new builder
     */
    public static OpenAPIObjectBuilder openAPIObject() {
        return new OpenAPIObjectBuilder();
    }
}