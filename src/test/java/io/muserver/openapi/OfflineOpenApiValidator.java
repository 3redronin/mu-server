package io.muserver.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

/** Test-only, pinned schema validation with no network fallback. */
public final class OfflineOpenApiValidator {
    public static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final JsonSchemaFactory FACTORY = factory();
    private static final String BASE = "https://spec.openapis.org/oas/3.2/schema-base/2026-08-30";

    private static JsonSchemaFactory factory() {
        try {
            Map<String, String> schemas = new HashMap<>();
            JsonNode manifest = JSON.readTree(resource("manifest.json"));
            Iterator<Map.Entry<String, JsonNode>> entries = manifest.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                byte[] bytes = resource(entry.getValue().get("resource").asText());
                StringBuilder checksum = new StringBuilder();
                for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) checksum.append(String.format(Locale.ROOT, "%02x", b));
                assertEquals(entry.getKey(), entry.getValue().get("sha256").asText(), checksum.toString());
                schemas.put(entry.getKey(), new String(bytes, StandardCharsets.UTF_8));
            }
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012, builder -> builder.schemaLoaders(loaders ->
                loaders.schemas(uri -> {
                    String schema = schemas.get(uri);
                    if (schema == null) throw new IllegalStateException("Unbundled schema requested: " + uri);
                    return schema;
                })));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = OfflineOpenApiValidator.class.getResourceAsStream("/openapi-3.2/" + name)) {
            if (in == null) throw new FileNotFoundException(name);
            return in.readAllBytes();
        }
    }

    public static JsonNode json(Object value) throws IOException {
        StringWriter out = new StringWriter();
        Jsonizer.writeValue(out, value);
        return JSON.readTree(out.toString());
    }

    public static void document(OpenAPIObject document) throws IOException { document(json(document)); }
    public static void document(JsonNode document) {
        Set<ValidationMessage> errors = FACTORY.getSchema(SchemaLocation.of(BASE)).validate(document);
        assertTrue(errors.toString(), errors.isEmpty());
    }
    public static void object(String model, JsonNode value) {
        String location;
        if (model.equals("OpenAPIObject")) { document(value); return; }
        if (model.equals("SchemaObject")) location = "https://spec.openapis.org/oas/3.2/dialect/2026-02-26";
        else if (model.equals("DiscriminatorObject") || model.equals("XmlObject")) location = "https://spec.openapis.org/oas/3.2/meta/2026-02-26#/$defs/" + (model.equals("XmlObject") ? "xml" : "discriminator");
        else if (model.equals("OAuthFlowObject")) throw new IllegalArgumentException("Validate OAuth flows in their containing OAuthFlowsObject slot");
        else {
            String name = model.substring(0, model.length() - "Object".length()).replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
            if (model.equals("OAuthFlowsObject")) name = "oauth-flows";
            if (model.equals("CallbackObject")) name = "callbacks";
            location = "https://spec.openapis.org/oas/3.2/schema/2026-08-30#/$defs/" + name;
        }
        Set<ValidationMessage> errors = FACTORY.getSchema(SchemaLocation.of(location)).validate(value);
        assertTrue(model + " " + value + ": " + errors, errors.isEmpty());
    }

    public static void accepts(SchemaObject schema, String payload, boolean expected) throws IOException {
        accepts(json(schema), payload, expected);
    }
    public static void accepts(JsonNode schema, String payload, boolean expected) throws IOException {
        Set<ValidationMessage> errors = FACTORY.getSchema(schema).validate(JSON.readTree(payload));
        assertEquals(schema + " validating " + payload + ": " + errors, expected, errors.isEmpty());
    }
}
