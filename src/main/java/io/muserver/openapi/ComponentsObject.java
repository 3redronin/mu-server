package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import java.util.regex.Pattern;

import static io.muserver.openapi.Jsonizer.append;

/**
 * @see ComponentsObjectBuilder
 */
public class ComponentsObject implements JsonWriter {

    private final @Nullable Map<String, SchemaObject> schemas;
    private final @Nullable Map<String, ResponseObject> responses;
    private final @Nullable Map<String, ParameterObject> parameters;
    private final @Nullable Map<String, ExampleObject> examples;
    private final @Nullable Map<String, RequestBodyObject> requestBodies;
    private final @Nullable Map<String, HeaderObject> headers;
    private final @Nullable Map<String, SecuritySchemeObject> securitySchemes;
    private final @Nullable Map<String, LinkObject> links;
    private final @Nullable Map<String, CallbackObject> callbacks;

    ComponentsObject(@Nullable Map<String, SchemaObject> schemas, @Nullable Map<String, ResponseObject> responses, @Nullable Map<String, ParameterObject> parameters, @Nullable Map<String, ExampleObject> examples, @Nullable Map<String, RequestBodyObject> requestBodies, @Nullable Map<String, HeaderObject> headers, @Nullable Map<String, SecuritySchemeObject> securitySchemes, @Nullable Map<String, LinkObject> links, @Nullable Map<String, CallbackObject> callbacks) {
        checkKey(schemas, responses, parameters, examples, requestBodies, headers, securitySchemes, links, callbacks);
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
        writer.write('}');
    }

    /**
     * @return The value described by {@link ComponentsObjectBuilder#withSchemas}
     */
    public @Nullable Map<String, SchemaObject> schemas() {
        return schemas;
    }

    /**
      @return The value described by {@link ComponentsObjectBuilder#withResponses}
     */
    public @Nullable Map<String, ResponseObject> responses() {
        return responses;
    }

    /**
      @return The value described by {@link ComponentsObjectBuilder#withParameters}
     */
    public @Nullable Map<String, ParameterObject> parameters() {
        return parameters;
    }

    /**
      @return The value described by {@link ComponentsObjectBuilder#withExamples}
     */
    public @Nullable Map<String, ExampleObject> examples() {
        return examples;
    }

    /**
      @return The value described by {@link ComponentsObjectBuilder#withRequestBodies}
     */
    public @Nullable Map<String, RequestBodyObject> requestBodies() {
        return requestBodies;
    }

    /**
      @return The value described by {@link ComponentsObjectBuilder#withHeaders}
     */
    public @Nullable Map<String, HeaderObject> headers() {
        return headers;
    }

    /**
      @return The value described by {@link ComponentsObjectBuilder#withSecuritySchemes}
     */
    public @Nullable Map<String, SecuritySchemeObject> securitySchemes() {
        return securitySchemes;
    }

    /**
      @return The value described by {@link ComponentsObjectBuilder#withLinks}
     */
    public @Nullable Map<String, LinkObject> links() {
        return links;
    }

    /**
      @return The value described by {@link ComponentsObjectBuilder#withCallbacks}
     */
    public @Nullable Map<String, CallbackObject> callbacks() {
        return callbacks;
    }
}
