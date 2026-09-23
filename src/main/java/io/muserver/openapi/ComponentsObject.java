package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import java.util.regex.Pattern;

import static io.muserver.openapi.Jsonizer.append;

/**
 * Holds reusable OpenAPI components referenced by the rest of the document.
 *
 * @see ComponentsObjectBuilder
 */
public class ComponentsObject implements JsonWriter {
    private final @Nullable Map<String, ReferenceOr<PathItemObject>> pathItems;
    private final @Nullable Map<String, ReferenceOr<MediaTypeObject>> mediaTypes;
    private final Map<String, Object> extensions;

    private final @Nullable Map<String, SchemaObject> schemas;
    private final @Nullable Map<String, ReferenceOr<ResponseObject>> responses;
    private final @Nullable Map<String, ReferenceOr<ParameterObject>> parameters;
    private final @Nullable Map<String, ReferenceOr<ExampleObject>> examples;
    private final @Nullable Map<String, ReferenceOr<RequestBodyObject>> requestBodies;
    private final @Nullable Map<String, ReferenceOr<HeaderObject>> headers;
    private final @Nullable Map<String, ReferenceOr<SecuritySchemeObject>> securitySchemes;
    private final @Nullable Map<String, ReferenceOr<LinkObject>> links;
    private final @Nullable Map<String, ReferenceOr<CallbackObject>> callbacks;

    ComponentsObject(@Nullable Map<String, SchemaObject> schemas, @Nullable Map<String, ReferenceOr<ResponseObject>> responses, @Nullable Map<String, ReferenceOr<ParameterObject>> parameters, @Nullable Map<String, ReferenceOr<ExampleObject>> examples, @Nullable Map<String, ReferenceOr<RequestBodyObject>> requestBodies, @Nullable Map<String, ReferenceOr<HeaderObject>> headers, @Nullable Map<String, ReferenceOr<SecuritySchemeObject>> securitySchemes, @Nullable Map<String, ReferenceOr<LinkObject>> links, @Nullable Map<String, ReferenceOr<CallbackObject>> callbacks, @Nullable Map<String, ReferenceOr<PathItemObject>> pathItems, @Nullable Map<String, ReferenceOr<MediaTypeObject>> mediaTypes, @Nullable Map<String, Object> extensions) {
        this.pathItems = OpenApiUtils.immutable(pathItems);
        this.mediaTypes = OpenApiUtils.immutable(mediaTypes);
        this.extensions = Extensions.copy(extensions);
        checkKey(schemas, responses, parameters, examples, requestBodies, headers, securitySchemes, links, callbacks, pathItems, mediaTypes);
        this.schemas = schemas;
        this.responses = responses;
        this.parameters = parameters;
        this.examples = examples;
        this.requestBodies = requestBodies;
        this.headers = headers;
        this.securitySchemes = securitySchemes;
        this.links = links;
        this.callbacks = callbacks;
    }

    private void checkKey(@Nullable Map<String, ?>... maps) {
        Pattern keyPattern = Pattern.compile("^[a-zA-Z0-9.\\-_]+$");

        for (@Nullable Map<String, ?> map : maps) {
            if (map != null) {
                for (String key : map.keySet()) {
                    if (!keyPattern.matcher(key).matches()) {
                        throw new IllegalArgumentException("The value '" + key + "' is not a valid key. It must match " + keyPattern);
                    }
                }
            }
        }
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "schemas", schemas, isFirst);
        isFirst = append(writer, "responses", responses, isFirst);
        isFirst = append(writer, "parameters", parameters, isFirst);
        isFirst = append(writer, "examples", examples, isFirst);
        isFirst = append(writer, "requestBodies", requestBodies, isFirst);
        isFirst = append(writer, "headers", headers, isFirst);
        isFirst = append(writer, "securitySchemes", securitySchemes, isFirst);
        isFirst = append(writer, "links", links, isFirst);
        isFirst = append(writer, "callbacks", callbacks, isFirst);
        isFirst = Jsonizer.append(writer, "pathItems", pathItems, isFirst);
        isFirst = Jsonizer.append(writer, "mediaTypes", mediaTypes, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write('}');
    }

    /**
     * Returns reusable schema definitions.
     *
     * @return The value described by {@link ComponentsObjectBuilder#withSchemas}
     */
    public @Nullable Map<String, SchemaObject> schemas() {
        return schemas;
    }

    /**
      * Returns reusable response definitions.
      *
      @return The value described by {@link ComponentsObjectBuilder#withResponses}
     */
    public @Nullable Map<String, ResponseObject> responses() {
        return ReferenceValues.values(responses);
    }

    /**
      * Returns reusable parameter definitions.
      *
      @return The value described by {@link ComponentsObjectBuilder#withParameters}
     */
    public @Nullable Map<String, ParameterObject> parameters() {
        return ReferenceValues.values(parameters);
    }

    /**
      * Returns reusable example definitions.
      *
      @return The value described by {@link ComponentsObjectBuilder#withExamples}
     */
    public @Nullable Map<String, ExampleObject> examples() {
        return ReferenceValues.values(examples);
    }

    /**
      * Returns reusable request-body definitions.
      *
      @return The value described by {@link ComponentsObjectBuilder#withRequestBodies}
     */
    public @Nullable Map<String, RequestBodyObject> requestBodies() {
        return ReferenceValues.values(requestBodies);
    }

    /**
      * Returns reusable header definitions.
      *
      @return The value described by {@link ComponentsObjectBuilder#withHeaders}
     */
    public @Nullable Map<String, HeaderObject> headers() {
        return ReferenceValues.values(headers);
    }

    /**
      * Returns reusable security-scheme definitions.
      *
      @return The value described by {@link ComponentsObjectBuilder#withSecuritySchemes}
     */
    public @Nullable Map<String, SecuritySchemeObject> securitySchemes() {
        return ReferenceValues.values(securitySchemes);
    }

    /**
      * Returns reusable link definitions.
      *
      @return The value described by {@link ComponentsObjectBuilder#withLinks}
     */
    public @Nullable Map<String, LinkObject> links() {
        return ReferenceValues.values(links);
    }

    /**
      * Returns reusable callback definitions.
      *
      @return The value described by {@link ComponentsObjectBuilder#withCallbacks}
     */
    public @Nullable Map<String, CallbackObject> callbacks() {
        return ReferenceValues.values(callbacks);
    }
    /** @return the pathItems value */
    public @Nullable Map<String, PathItemObject> pathItems() { return ReferenceValues.values(pathItems); }
    /** @return inline path items and references */
    public @Nullable Map<String, ReferenceOr<PathItemObject>> pathItemsOrReferences() { return pathItems; }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return inline values and references for responses */
    public @Nullable Map<String, ReferenceOr<ResponseObject>> responsesOrReferences() { return responses; }
    /** @return inline values and references for parameters */
    public @Nullable Map<String, ReferenceOr<ParameterObject>> parametersOrReferences() { return parameters; }
    /** @return inline values and references for examples */
    public @Nullable Map<String, ReferenceOr<ExampleObject>> examplesOrReferences() { return examples; }
    /** @return inline values and references for requestBodies */
    public @Nullable Map<String, ReferenceOr<RequestBodyObject>> requestBodiesOrReferences() { return requestBodies; }
    /** @return inline values and references for headers */
    public @Nullable Map<String, ReferenceOr<HeaderObject>> headersOrReferences() { return headers; }
    /** @return inline values and references for securitySchemes */
    public @Nullable Map<String, ReferenceOr<SecuritySchemeObject>> securitySchemesOrReferences() { return securitySchemes; }
    /** @return inline values and references for links */
    public @Nullable Map<String, ReferenceOr<LinkObject>> linksOrReferences() { return links; }
    /** @return inline values and references for callbacks */
    public @Nullable Map<String, ReferenceOr<CallbackObject>> callbacksOrReferences() { return callbacks; }
    /** @return a builder preserving all fields and extensions */
    public ComponentsObjectBuilder toBuilder() {
        return new ComponentsObjectBuilder()
            .withPathItemsOrReferences(pathItems).withMediaTypesOrReferences(mediaTypes).withExtensions(extensions).withSchemas(schemas).withResponsesOrReferences(responses).withParametersOrReferences(parameters).withExamplesOrReferences(examples).withRequestBodiesOrReferences(requestBodies).withHeadersOrReferences(headers).withSecuritySchemesOrReferences(securitySchemes).withLinksOrReferences(links).withCallbacksOrReferences(callbacks);
    }
    /**
     * @return the reusable media types keyed by component name, or null when omitted
     * @see ComponentsObjectBuilder#withMediaTypesOrReferences
     */
    public @Nullable Map<String, ReferenceOr<MediaTypeObject>> mediaTypesOrReferences() { return mediaTypes; }
    /** @return inline media types; throws if a reference is present */
    public @Nullable Map<String, MediaTypeObject> mediaTypes() { return ReferenceValues.values(mediaTypes); }
}
