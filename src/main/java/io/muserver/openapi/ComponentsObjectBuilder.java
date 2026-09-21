package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.util.Map;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * Holds a set of reusable objects for different aspects of the OAS. All objects defined within the components object
 * will have no effect on the API unless they are explicitly referenced from properties outside the components object.
 */
public class ComponentsObjectBuilder {
    private @Nullable Map<String, ReferenceOr<PathItemObject>> pathItems;
    private @Nullable Map<String, ReferenceOr<MediaTypeObject>> mediaTypes;
    private @Nullable Map<String, Object> extensions;
    @Nullable Map<String, SchemaObject> schemas;
    private @Nullable Map<String, ReferenceOr<ResponseObject>> responses;
    private @Nullable Map<String, ReferenceOr<ParameterObject>> parameters;
    private @Nullable Map<String, ReferenceOr<ExampleObject>> examples;
    private @Nullable Map<String, ReferenceOr<RequestBodyObject>> requestBodies;
    private @Nullable Map<String, ReferenceOr<HeaderObject>> headers;
    private @Nullable Map<String, ReferenceOr<SecuritySchemeObject>> securitySchemes;
    private @Nullable Map<String, ReferenceOr<LinkObject>> links;
    private @Nullable Map<String, ReferenceOr<CallbackObject>> callbacks;

    /**
     *
     * @param schemas An object to hold reusable Schema Objects.
     *
     * @return The current builder
     */
    public ComponentsObjectBuilder withSchemas(@Nullable Map<String, SchemaObject> schemas) {
        this.schemas = schemas;
        return this;
    }

    /**
     *
     * @param responses An object to hold reusable Response Objects.
     *
     * @return The current builder
     */
    public ComponentsObjectBuilder withResponses(@Nullable Map<String, ResponseObject> responses) {
        this.responses = ReferenceValues.inline(responses);
        return this;
    }

    /**
     *
     * @param parameters An object to hold reusable Parameter Objects.
     *
     * @return The current builder
     */
    public ComponentsObjectBuilder withParameters(@Nullable Map<String, ParameterObject> parameters) {
        this.parameters = ReferenceValues.inline(parameters);
        return this;
    }

    /**
     *
     * @param examples An object to hold reusable Example Objects.
     *
     * @return The current builder
     */
    public ComponentsObjectBuilder withExamples(@Nullable Map<String, ExampleObject> examples) {
        this.examples = ReferenceValues.inline(examples);
        return this;
    }

    /**
     *
     * @param requestBodies An object to hold reusable Request Body Objects.
     *
     * @return The current builder
     */
    public ComponentsObjectBuilder withRequestBodies(@Nullable Map<String, RequestBodyObject> requestBodies) {
        this.requestBodies = ReferenceValues.inline(requestBodies);
        return this;
    }

    /**
     *
     * @param headers An object to hold reusable Header Objects.
     *
     * @return The current builder
     */
    public ComponentsObjectBuilder withHeaders(@Nullable Map<String, HeaderObject> headers) {
        this.headers = ReferenceValues.inline(headers);
        return this;
    }

    /**
     *
     * @param securitySchemes An object to hold reusable Security Scheme Objects.
     *
     * @return The current builder
     */
    public ComponentsObjectBuilder withSecuritySchemes(@Nullable Map<String, SecuritySchemeObject> securitySchemes) {
        this.securitySchemes = ReferenceValues.inline(securitySchemes);
        return this;
    }

    /**
     *
     * @param links An object to hold reusable Link Objects.
     *
     * @return The current builder
     */
    public ComponentsObjectBuilder withLinks(@Nullable Map<String, LinkObject> links) {
        this.links = ReferenceValues.inline(links);
        return this;
    }

    /**
     *
     * @param callbacks An object to hold reusable Callback Objects.
     *
     * @return The current builder
     */
    public ComponentsObjectBuilder withCallbacks(@Nullable Map<String, CallbackObject> callbacks) {
        this.callbacks = ReferenceValues.inline(callbacks);
        return this;
    }

    /**
     * @return A new object
     */
    public ComponentsObject build() {
        return new ComponentsObject(immutable(schemas), immutable(responses), immutable(parameters), immutable(examples),
            immutable(requestBodies), immutable(headers), immutable(securitySchemes), immutable(links), immutable(callbacks), pathItems, mediaTypes, extensions);
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
     *
     * @param toCopy A component to copy. If <code>null</code> then an empty builder is returned.
     *
     * @return A new builder pre-populated with values from an existing component
     */
    public static ComponentsObjectBuilder componentsObject(@Nullable ComponentsObject toCopy) {
        return toCopy == null ? componentsObject() : toCopy.toBuilder();
    }
    /**
     * @param value the pathItems value
     * @return this builder */
    public ComponentsObjectBuilder withPathItemsOrReferences(@Nullable Map<String, ReferenceOr<PathItemObject>> value) { this.pathItems = value; return this; }
    /**
     * @param value the extensions value
     * @return this builder */
    public ComponentsObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public ComponentsObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
    /**
     * @param value inline values and references for responses
     * @return this builder */
    public ComponentsObjectBuilder withResponsesOrReferences(@Nullable Map<String, ReferenceOr<ResponseObject>> value) { this.responses = value; return this; }
    /**
     * @param value inline values and references for parameters
     * @return this builder */
    public ComponentsObjectBuilder withParametersOrReferences(@Nullable Map<String, ReferenceOr<ParameterObject>> value) { this.parameters = value; return this; }
    /**
     * @param value inline values and references for examples
     * @return this builder */
    public ComponentsObjectBuilder withExamplesOrReferences(@Nullable Map<String, ReferenceOr<ExampleObject>> value) { this.examples = value; return this; }
    /**
     * @param value inline values and references for requestBodies
     * @return this builder */
    public ComponentsObjectBuilder withRequestBodiesOrReferences(@Nullable Map<String, ReferenceOr<RequestBodyObject>> value) { this.requestBodies = value; return this; }
    /**
     * @param value inline values and references for headers
     * @return this builder */
    public ComponentsObjectBuilder withHeadersOrReferences(@Nullable Map<String, ReferenceOr<HeaderObject>> value) { this.headers = value; return this; }
    /**
     * @param value inline values and references for securitySchemes
     * @return this builder */
    public ComponentsObjectBuilder withSecuritySchemesOrReferences(@Nullable Map<String, ReferenceOr<SecuritySchemeObject>> value) { this.securitySchemes = value; return this; }
    /**
     * @param value inline values and references for links
     * @return this builder */
    public ComponentsObjectBuilder withLinksOrReferences(@Nullable Map<String, ReferenceOr<LinkObject>> value) { this.links = value; return this; }
    /**
     * @param value inline values and references for callbacks
     * @return this builder */
    public ComponentsObjectBuilder withCallbacksOrReferences(@Nullable Map<String, ReferenceOr<CallbackObject>> value) { this.callbacks = value; return this; }
    /**
     * @param value inline path items
     * @return this builder */
    public ComponentsObjectBuilder withPathItems(@Nullable Map<String, PathItemObject> value) { this.pathItems = ReferenceValues.inline(value); return this; }
    /**
     * @param value the OpenAPI 3.2 mediaTypes value
     * @return this builder
     */
    public ComponentsObjectBuilder withMediaTypesOrReferences(@Nullable Map<String, ReferenceOr<MediaTypeObject>> value) { this.mediaTypes = value; return this; }
    /** @param value reusable inline media types
     * @return this builder */
    public ComponentsObjectBuilder withMediaTypes(@Nullable Map<String, MediaTypeObject> value) { this.mediaTypes = ReferenceValues.inline(value); return this; }
}
