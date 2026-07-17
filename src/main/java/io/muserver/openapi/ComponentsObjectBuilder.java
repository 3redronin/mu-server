package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * Holds a set of reusable objects for different aspects of the OAS. All objects defined within the components object
 * will have no effect on the API unless they are explicitly referenced from properties outside the components object.
 */
public class ComponentsObjectBuilder {
    @Nullable Map<String, SchemaObject> schemas;
    private @Nullable Map<String, ResponseObject> responses;
    private @Nullable Map<String, ParameterObject> parameters;
    private @Nullable Map<String, ExampleObject> examples;
    private @Nullable Map<String, RequestBodyObject> requestBodies;
    private @Nullable Map<String, HeaderObject> headers;
    private @Nullable Map<String, SecuritySchemeObject> securitySchemes;
    private @Nullable Map<String, LinkObject> links;
    private @Nullable Map<String, CallbackObject> callbacks;

    /**
     * @param schemas An object to hold reusable Schema Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withSchemas(@Nullable Map<String, SchemaObject> schemas) {
        this.schemas = schemas;
        return this;
    }

    /**
     * @param responses An object to hold reusable Response Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withResponses(@Nullable Map<String, ResponseObject> responses) {
        this.responses = responses;
        return this;
    }

    /**
     * @param parameters An object to hold reusable Parameter Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withParameters(@Nullable Map<String, ParameterObject> parameters) {
        this.parameters = parameters;
        return this;
    }

    /**
     * @param examples An object to hold reusable Example Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withExamples(@Nullable Map<String, ExampleObject> examples) {
        this.examples = examples;
        return this;
    }

    /**
     * @param requestBodies An object to hold reusable Request Body Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withRequestBodies(@Nullable Map<String, RequestBodyObject> requestBodies) {
        this.requestBodies = requestBodies;
        return this;
    }

    /**
     * @param headers An object to hold reusable Header Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withHeaders(@Nullable Map<String, HeaderObject> headers) {
        this.headers = headers;
        return this;
    }

    /**
     * @param securitySchemes An object to hold reusable Security Scheme Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withSecuritySchemes(@Nullable Map<String, SecuritySchemeObject> securitySchemes) {
        this.securitySchemes = securitySchemes;
        return this;
    }

    /**
     * @param links An object to hold reusable Link Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withLinks(@Nullable Map<String, LinkObject> links) {
        this.links = links;
        return this;
    }

    /**
     * @param callbacks An object to hold reusable Callback Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withCallbacks(@Nullable Map<String, CallbackObject> callbacks) {
        this.callbacks = callbacks;
        return this;
    }

    /**
     * @return A new object
     */
    public ComponentsObject build() {
        return new ComponentsObject(immutable(schemas), immutable(responses), immutable(parameters), immutable(examples),
            immutable(requestBodies), immutable(headers), immutable(securitySchemes), immutable(links), immutable(callbacks));
    }

    /**
     * Creates a builder for a {@link ComponentsObject}
     *
     * @return A new builder
     */
    public static ComponentsObjectBuilder componentsObject() {
        return new ComponentsObjectBuilder();
    }

    /**
     * Creates a builder for a {@link ComponentsObject} based on an existing components object
     * @param toCopy A component to copy. If <code>null</code> then an empty builder is returned.
     * @return A new builder pre-populated with values from an existing component
     */
    public static ComponentsObjectBuilder componentsObject(@Nullable ComponentsObject toCopy) {
        ComponentsObjectBuilder builder = componentsObject();
        if (toCopy != null) {
            builder
                .withCallbacks(toCopy.callbacks())
                .withExamples(toCopy.examples())
                .withHeaders(toCopy.headers())
                .withLinks(toCopy.links())
                .withParameters(toCopy.parameters())
                .withRequestBodies(toCopy.requestBodies())
                .withResponses(toCopy.responses())
                .withSecuritySchemes(toCopy.securitySchemes())
                .withSchemas(toCopy.schemas());

        }
        return builder;
    }
}
