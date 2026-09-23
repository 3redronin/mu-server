package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import java.util.List;

import static io.muserver.openapi.InfoObjectBuilder.infoObject;
import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * This is the root document object of the OpenAPI document.
 */
public class OpenAPIObjectBuilder {
    private @Nullable String jsonSchemaDialect;
    private @Nullable Map<String, ReferenceOr<PathItemObject>> webhooks;
    private @Nullable String self;
    private @Nullable Map<String, Object> extensions;
    private @Nullable InfoObject info;
    private @Nullable List<ServerObject> servers;
    private @Nullable PathsObject paths;
    private @Nullable ComponentsObject components;
    private @Nullable List<SecurityRequirementObject> security;
    private @Nullable List<TagObject> tags;
    private @Nullable ExternalDocumentationObject externalDocs;

    /**
     *
     * @param info <strong>REQUIRED</strong>. Provides metadata about the API. The metadata MAY be used by tooling as required.
     *
     * @return The current builder
     */
    public OpenAPIObjectBuilder withInfo(InfoObject info) {
        this.info = info;
        return this;
    }

    /**
     *
     * @param servers An array of Server Objects, which provide connectivity information to a target server. If the <code>servers</code>
     *                property is not provided, or is an empty array, the default value would be a {@link ServerObject} with a
     *                <code>url</code> value of <code>/</code>.
     *
     * @return The current builder
     */
    public OpenAPIObjectBuilder withServers(@Nullable List<ServerObject> servers) {
        this.servers = servers;
        return this;
    }

    /**
     *
     * @param paths The available paths and operations, or null. At least one of paths, components, or webhooks must be supplied.
     *
     * @return The current builder
     */
    public OpenAPIObjectBuilder withPaths(@Nullable PathsObject paths) {
        this.paths = paths;
        return this;
    }

    /**
     *
     * @param components An element to hold various schemas for the specification.
     *
     * @return The current builder
     */
    public OpenAPIObjectBuilder withComponents(@Nullable ComponentsObject components) {
        this.components = components;
        return this;
    }

    /**
     *
     * @param security A declaration of which security mechanisms can be used across the API. The list of values includes
     *                 alternative security requirement objects that can be used. Only one of the security requirement
     *                 objects need to be satisfied to authorize a request. Individual operations can override this definition.
     *
     * @return The current builder
     */
    public OpenAPIObjectBuilder withSecurity(@Nullable List<SecurityRequirementObject> security) {
        this.security = security;
        return this;
    }

    /**
     *
     * @param tags A list of tags used by the specification with additional metadata. The order of the tags can be used
     *             to reflect on their order by the parsing tools. Not all tags that are used by the {@link OperationObject} must
     *             be declared. The tags that are not declared MAY be organized randomly or based on the tools' logic.
     *             Each tag name in the list MUST be unique.
     *
     * @return The current builder
     */
    public OpenAPIObjectBuilder withTags(@Nullable List<TagObject> tags) {
        this.tags = tags;
        return this;
    }

    /**
     *
     * @param externalDocs Additional external documentation.
     *
     * @return The current builder
     */
    public OpenAPIObjectBuilder withExternalDocs(@Nullable ExternalDocumentationObject externalDocs) {
        this.externalDocs = externalDocs;
        return this;
    }

    /**
     * @return A new object
     */
    public OpenAPIObject build() {
        InfoObject infoToUse = this.info == null ? infoObject().build() : this.info;
        return new OpenAPIObject(infoToUse, immutable(servers), paths, components, immutable(security), immutable(tags), externalDocs, jsonSchemaDialect, webhooks, self, extensions);
    }

    /**
     * Creates a builder for an {@link OpenAPIObject}
     *
     * @return A new builder
     */
    public static OpenAPIObjectBuilder openAPIObject() {
        return new OpenAPIObjectBuilder();
    }
    /**
     * @param value the jsonSchemaDialect value
     * @return this builder */
    public OpenAPIObjectBuilder withJsonSchemaDialect(@Nullable String value) { this.jsonSchemaDialect = value; return this; }
    /**
     * @param value the webhooks value
     * @return this builder */
    public OpenAPIObjectBuilder withWebhooksOrReferences(@Nullable Map<String, ReferenceOr<PathItemObject>> value) { this.webhooks = value; return this; }
    /**
     * @param value the extensions value
     * @return this builder */
    public OpenAPIObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public OpenAPIObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
    /**
     * @param value inline path items
     * @return this builder */
    public OpenAPIObjectBuilder withWebhooks(@Nullable Map<String, PathItemObject> value) { this.webhooks = ReferenceValues.inline(value); return this; }
    /** @return the configured paths, or null if absent */
    public @Nullable PathsObject paths() { return paths; }
    /**
     * Sets the document URI used as the base for resolving relative references within this API description.
     * The URI may be relative, but must not contain a fragment.
     * This identifies the description document, not an API server endpoint.
     *
     * @param value the document URI reference, or null to omit it
     * @return this builder
     */
    public OpenAPIObjectBuilder withSelf(@Nullable String value) { this.self = value; return this; }
}
