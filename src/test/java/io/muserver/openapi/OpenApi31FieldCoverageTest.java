package io.muserver.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import java.io.*;
import java.lang.reflect.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static io.muserver.openapi.OfflineOpenApiValidator.*;
import static org.junit.Assert.*;

/** Executable field checklist: builder, getter, JSON, immutable copying, and extension isolation. */
@RunWith(Parameterized.class)
public class OpenApi31FieldCoverageTest {
    @Parameterized.Parameters(name="{0}.{1}") public static Collection<Object[]> fields() throws Exception {
        List<Object[]> fields = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            Objects.requireNonNull(OpenApi31FieldCoverageTest.class.getResourceAsStream("/openapi-3.1/field-coverage.tsv")), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) if (!line.startsWith("#")) fields.add(line.split("\t"));
        }
        return fields;
    }
    private final String model, keyword, getter, setter;
    public OpenApi31FieldCoverageTest(String model, String keyword, String getter, String setter) {
        this.model = model; this.keyword = keyword; this.getter = getter; this.setter = setter;
    }

    @Test public void fieldSurvivesBuildSerializationAndCopy() throws Exception {
        Class<?> type = Class.forName("io.muserver.openapi." + model);
        Object builder = fixture(type).getClass().getMethod("toBuilder").invoke(fixture(type));
        Method method = Arrays.stream(builder.getClass().getMethods()).filter(m -> m.getName().equals(setter)).findFirst().orElseThrow();
        Object value = sample(method.getGenericParameterTypes()[0], keyword, model);
        if (model.equals("LicenseObject") && keyword.equals("identifier")) call(builder, "withUrl", null);
        if (model.equals("LicenseObject") && keyword.equals("url")) call(builder, "withIdentifier", null);
        if ((model.equals("ParameterObject") || model.equals("HeaderObject")) && keyword.equals("content")) call(builder, "withSchema", null);
        if (model.equals("LinkObject") && keyword.equals("operationRef")) call(builder, "withOperationId", null);
        if (model.equals("SecuritySchemeObject")) {
            if (Arrays.asList("name", "in", "flows", "openIdConnectUrl").contains(keyword)) call(builder, "withScheme", null);
            if (Arrays.asList("name", "in").contains(keyword)) { call(builder, "withType", "apiKey"); call(builder, "withName", "auth"); call(builder, "withIn", "query"); }
            if (keyword.equals("flows")) call(builder, "withType", "oauth2");
            if (keyword.equals("openIdConnectUrl")) call(builder, "withType", "openIdConnect");
        }
        if (model.equals("SecuritySchemeObject") && keyword.equals("oauth2MetadataUrl")) {
            call(builder, "withScheme", null); call(builder, "withType", "oauth2");
            call(builder, "withFlows", OAuthFlowsObjectBuilder.oAuthFlowsObject().build());
        }
        if (model.equals("OAuthFlowObject") && keyword.equals("deviceAuthorizationUrl")) call(builder, "withAuthorizationUrl", null);
        method.invoke(builder, value);
        Object built = builder.getClass().getMethod("build").invoke(builder);
        assertNotNull(type.getMethod(getter).invoke(built));
        JsonNode document = json(built);
        OfflineOpenApiValidator.object(model, document);
        // These explicit values equal their context-dependent defaults and are intentionally omitted.
        if (!(keyword.equals("style") && model.equals("HeaderObject"))) assertTrue(document.toString(), document.has(keyword));
        Object copy = type.getMethod("toBuilder").invoke(built);
        assertEquals(document, json(copy.getClass().getMethod("build").invoke(copy)));
        if (!model.equals("ReferenceObject") && !model.equals("SecurityRequirementObject")) {
            Method extension = builder.getClass().getMethod("withExtension", String.class, Object.class);
            extension.invoke(builder, "x-test", Arrays.asList(1, JsonNull.INSTANCE));
            assertTrue(json(builder.getClass().getMethod("build").invoke(builder)).has("x-test"));
            assertFalse(json(built).has("x-test"));
            InvocationTargetException error = assertThrows(InvocationTargetException.class, () -> extension.invoke(builder, "description", "collision"));
            assertTrue(error.getCause() instanceof IllegalArgumentException);
        }
    }

    private static void call(Object builder, String name, Object value) throws Exception {
        for (Method method : builder.getClass().getMethods()) if (method.getName().equals(name) && method.getParameterCount() == 1) { method.invoke(builder, value); return; }
        throw new NoSuchMethodException(name);
    }

    private static Object sample(Type type, String field, String model) throws Exception {
        if (type instanceof ParameterizedType) {
            ParameterizedType generic = (ParameterizedType) type;
            if (generic.getRawType() == List.class) return Collections.singletonList(sample(generic.getActualTypeArguments()[0], field, model));
            if (generic.getRawType() == Map.class) {
                String key = field.equals("content") ? "application/json" : field.equals("get") ? "get" : field.startsWith("/") ? "/item" : field.equals("200") ? "200" : field.startsWith("{") ? field : field.equals("$vocabulary") ? "https://example.test/vocabulary" : "auth";
                return Collections.singletonMap(key, sample(generic.getActualTypeArguments()[1], field, model));
            }
            if (generic.getRawType() == ReferenceOr.class) return ReferenceOr.inline(sample(generic.getActualTypeArguments()[0], field, model));
        }
        if (type == String.class) {
            if (field.equals("nodeType")) return "element";
            if (field.equals("type")) return model.equals("SecuritySchemeObject") ? "http" : "string";
            if (field.equals("in")) return "query";
            if (field.equals("style")) return model.equals("HeaderObject") ? "simple" : "pipeDelimited";
            if (field.equals("email")) return "a@example.test";
            if (field.equals("$schema") || field.equals("jsonSchemaDialect")) return "https://spec.openapis.org/oas/3.2/dialect/2026-02-26";
            if (field.equals("$ref")) return "#/components/schemas/Value";
            if (field.equals("operationRef")) return "#/paths/~1item/get";
            if (field.equals("scheme")) return "bearer";
            if (field.equals("default")) return "value";
            if (field.equals("enum")) return "value";
            return "value";
        }
        if (type == URI.class) return URI.create("https://example.test/value");
        if (type == Boolean.class || type == boolean.class) return !field.equals("explode") || model.equals("HeaderObject");
        if (type == Number.class || type == Double.class) return 2.5;
        if (type == Integer.class) return 2;
        if (type == Object.class) return field.equals("additionalProperties") ? Boolean.FALSE : "value";
        Object fixture = fixture((Class<?>) type);
        if (type == OAuthFlowObject.class) {
            OAuthFlowObjectBuilder flow = ((OAuthFlowObject) fixture).toBuilder();
            if (field.equals("deviceAuthorization")) flow.withAuthorizationUrl(null).withDeviceAuthorizationUrl(URI.create("https://example.test/device"));
            if (field.equals("implicit")) flow.withTokenUrl(null);
            if (field.equals("password") || field.equals("clientCredentials")) flow.withAuthorizationUrl(null);
            return flow.build();
        }
        return fixture;
    }

    static Object fixture(Class<?> type) throws Exception {
        switch (type.getSimpleName()) {
            case "OpenAPIObject": return OpenAPIObjectBuilder.openAPIObject().withPaths(PathsObjectBuilder.pathsObject().build()).build();
            case "ParameterObject": return ParameterObjectBuilder.parameterObject().withName("value").withIn("query").withSchema(SchemaObjectBuilder.schemaObject().build()).build();
            case "HeaderObject": return HeaderObjectBuilder.headerObject().withSchema(SchemaObjectBuilder.schemaObject().build()).build();
            case "ResponsesObject": return ResponsesObjectBuilder.responsesObject().withHttpStatusCodes(Collections.singletonMap("200", (ResponseObject) fixture(ResponseObject.class))).build();
            case "ResponseObject": return ResponseObjectBuilder.responseObject().withDescription("Success").build();
            case "RequestBodyObject": return RequestBodyObjectBuilder.requestBodyObject().withContent(Collections.singletonMap("application/json", MediaTypeObjectBuilder.mediaTypeObject().build())).build();
            case "ServerObject": return ServerObjectBuilder.serverObject().withUrl("https://example.test").build();
            case "ServerVariableObject": return ServerVariableObjectBuilder.serverVariableObject().withDefaultValue("value").build();
            case "ExternalDocumentationObject": return ExternalDocumentationObjectBuilder.externalDocumentationObject().withUrl(URI.create("https://example.test")).build();
            case "LicenseObject": return LicenseObjectBuilder.licenseObject().withName("MIT").build();
            case "TagObject": return TagObjectBuilder.tagObject().withName("tag").build();
            case "DiscriminatorObject": return DiscriminatorObjectBuilder.discriminatorObject().withPropertyName("type").build();
            case "SecuritySchemeObject": return SecuritySchemeObjectBuilder.securitySchemeObject().withType("http").withScheme("bearer").build();
            case "OAuthFlowObject": return OAuthFlowObjectBuilder.oAuthFlowObject().withAuthorizationUrl(URI.create("https://example.test/auth")).withTokenUrl(URI.create("https://example.test/token")).withScopes(Collections.emptyMap()).build();
            case "CallbackObject": return CallbackObjectBuilder.callbackObject().withCallbacks(Collections.emptyMap()).build();
            case "SecurityRequirementObject": return SecurityRequirementObjectBuilder.securityRequirementObject().withRequirements(Collections.emptyMap()).build();
            case "LinkObject": return LinkObjectBuilder.linkObject().withOperationId("operation").build();
            case "ReferenceObject": return ReferenceObjectBuilder.referenceObject().withRef("#/components/schemas/Value").build();
            default: return Class.forName(type.getName() + "Builder").getMethod("build").invoke(Class.forName(type.getName() + "Builder").getConstructor().newInstance());
        }
    }
}
