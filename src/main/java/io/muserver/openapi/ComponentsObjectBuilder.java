package io.muserver.openapi;

import java.util.Map;

/**
 * Holds a set of reusable objects for different aspects of the OAS. All objects defined within the components object
 * will have no effect on the API unless they are explicitly referenced from properties outside the components object.
 */
public class ComponentsObjectBuilder {
    private Map<String, SchemaObject> schemas;
    private Map<String, ResponseObject> responses;
    private Map<String, ParameterObject> parameters;
    private Map<String, ExampleObject> examples;
    private Map<String, RequestBodyObject> requestBodies;
    private Map<String, HeaderObject> headers;
    private Map<String, SecuritySchemeObject> securitySchemes;
    private Map<String, LinkObject> links;
    private Map<String, CallbackObject> callbacks;

    /**
     * @param schemas An object to hold reusable Schema Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withSchemas(Map<String, SchemaObject> schemas) {
        this.schemas = schemas;
        return this;
    }

    /**
     * @param responses An object to hold reusable Response Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withResponses(Map<String, ResponseObject> responses) {
        this.responses = responses;
        return this;
    }

    /**
     * @param parameters An object to hold reusable Parameter Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withParameters(Map<String, ParameterObject> parameters) {
        this.parameters = parameters;
        return this;
    }

    /**
     * @param examples An object to hold reusable Example Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withExamples(Map<String, ExampleObject> examples) {
        this.examples = examples;
        return this;
    }

    /**
     * @param requestBodies An object to hold reusable Request Body Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withRequestBodies(Map<String, RequestBodyObject> requestBodies) {
        this.requestBodies = requestBodies;
        return this;
    }

    /**
     * @param headers An object to hold reusable Header Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withHeaders(Map<String, HeaderObject> headers) {
        this.headers = headers;
        return this;
    }

    /**
     * @param securitySchemes An object to hold reusable Security Scheme Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withSecuritySchemes(Map<String, SecuritySchemeObject> securitySchemes) {
        this.securitySchemes = securitySchemes;
        return this;
    }

    /**
     * @param links An object to hold reusable Link Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withLinks(Map<String, LinkObject> links) {
        this.links = links;
        return this;
    }

    /**
     * @param callbacks An object to hold reusable Callback Objects.
     * @return The current builder
     */
    public ComponentsObjectBuilder withCallbacks(Map<String, CallbackObject> callbacks) {
        this.callbacks = callbacks;
        return this;
    }

    public ComponentsObject build() {
        return new ComponentsObject(schemas, responses, parameters, examples, requestBodies, headers, securitySchemes, links, callbacks);
    }

    /**
     * Creates a builder for a {@link ComponentsObject}
     *
     * @return A new builder
     */
    public static ComponentsObjectBuilder componentsObject() {
        return new ComponentsObjectBuilder();
    }
}