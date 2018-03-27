package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;
import java.util.regex.Pattern;

import static io.muserver.openapi.Jsonizer.append;

/**
 * @see ComponentsObjectBuilder
 */
public class ComponentsObject implements JsonWriter {

    public final Map<String, SchemaObject> schemas;
    public final Map<String, ResponseObject> responses;
    public final Map<String, ParameterObject> parameters;
    public final Map<String, ExampleObject> examples;
    public final Map<String, RequestBodyObject> requestBodies;
    public final Map<String, HeaderObject> headers;
    public final Map<String, SecuritySchemeObject> securitySchemes;
    public final Map<String, LinkObject> links;
    public final Map<String, CallbackObject> callbacks;

    ComponentsObject(Map<String, SchemaObject> schemas, Map<String, ResponseObject> responses, Map<String, ParameterObject> parameters, Map<String, ExampleObject> examples, Map<String, RequestBodyObject> requestBodies, Map<String, HeaderObject> headers, Map<String, SecuritySchemeObject> securitySchemes, Map<String, LinkObject> links, Map<String, CallbackObject> callbacks) {
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

    private void checkKey(Map<String, ?>... maps) {
        Pattern keyPattern = Pattern.compile("^[a-zA-Z0-9.\\-_]+$");

        for (Map<String, ?> map : maps) {
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
}
