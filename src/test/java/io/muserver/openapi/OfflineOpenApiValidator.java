package io.muserver.openapi;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONTokener;
import com.networknt.schema.*;
import com.networknt.schema.serialization.JsonMapperFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Test-only, pinned schema validation with no network fallback. */
public final class OfflineOpenApiValidator {
    private static final SchemaRegistry REGISTRY = registry();
    private static final String BASE = "https://spec.openapis.org/oas/3.2/schema-base/2026-08-30";

    private static SchemaRegistry registry() {
        try {
            Map<String, String> schemas = new HashMap<>();
            JSONObject manifest = new JSONObject(new String(resource("manifest.json"), StandardCharsets.UTF_8));
            for (String uri : manifest.keySet()) {
                JSONObject entry = manifest.getJSONObject(uri);
                byte[] bytes = resource(entry.getString("resource"));
                StringBuilder checksum = new StringBuilder();
                for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) checksum.append(String.format(Locale.ROOT, "%02x", b));
                assertEquals(entry.getString("sha256"), checksum.toString(), uri);
                schemas.put(uri, new String(bytes, StandardCharsets.UTF_8));
            }
            return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12, builder ->
                // The validator's default parser rounds decimal bounds through double.
                builder.nodeReader(reader -> reader.jsonMapper(JsonMapperFactory.getInstance().copy()
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)))
                .schemas(uri -> {
                    String schema = schemas.get(uri);
                    if (schema == null) throw new IllegalStateException("Unbundled schema requested: " + uri);
                    return schema;
                }));
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = OfflineOpenApiValidator.class.getResourceAsStream("/openapi/" + name)) {
            if (in == null) throw new FileNotFoundException(name);
            return in.readAllBytes();
        }
    }

    public static JSONObject json(Object value) throws IOException {
        return (JSONObject) jsonValue(value);
    }

    public static Object jsonValue(Object value) throws IOException {
        StringWriter out = new StringWriter();
        Jsonizer.writeValue(out, value);
        return new JSONTokener(out.toString()).nextValue();
    }

    public static void assertJsonEquals(Object expected, Object actual) {
        assertJsonEquals("JSON values differ", expected, actual);
    }

    public static void assertJsonEquals(String message, Object expected, Object actual) {
        if (expected instanceof JSONObject) assertTrue(((JSONObject) expected).similar(actual), message + ": " + expected + " != " + actual);
        else if (expected instanceof JSONArray) assertTrue(((JSONArray) expected).similar(actual), message + ": " + expected + " != " + actual);
        else assertEquals(expected, actual, message);
    }

    public static void document(OpenAPIObject document) throws IOException { document(json(document)); }
    public static void document(JSONObject document) {
        List<com.networknt.schema.Error> errors = REGISTRY.getSchema(SchemaLocation.of(BASE)).validate(document.toString(), InputFormat.JSON);
        assertTrue(errors.isEmpty(), errors.toString());
    }
    public static void object(String model, JSONObject value) {
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
        List<com.networknt.schema.Error> errors = REGISTRY.getSchema(SchemaLocation.of(location)).validate(value.toString(), InputFormat.JSON);
        assertTrue(errors.isEmpty(), model + " " + value + ": " + errors);
    }

    public static void accepts(SchemaObject schema, String payload, boolean expected) throws IOException {
        accepts(jsonValue(schema), payload, expected);
    }
    public static void accepts(Object schema, String payload, boolean expected) throws IOException {
        List<com.networknt.schema.Error> errors = REGISTRY.getSchema(schema.toString(), InputFormat.JSON).validate(payload, InputFormat.JSON);
        assertEquals(expected, errors.isEmpty(), schema + " validating " + payload + ": " + errors);
    }
}
