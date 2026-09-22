package io.muserver.openapi;

import org.junit.Test;
import java.math.*;
import java.io.*;
import java.util.*;
import static io.muserver.openapi.SchemaObjectBuilder.*;
import static io.muserver.openapi.OfflineOpenApiValidator.*;
import static org.junit.Assert.*;

public class OpenApi31SchemaTest {
    @Test public void integralKeywordViewsAcceptDifferentNumberRepresentations() throws Exception {
        for (String keyword : Arrays.asList("maxLength", "minLength", "maxItems", "minItems",
            "maxProperties", "minProperties", "minContains", "maxContains")) {
            for (Number value : Arrays.asList(1L, 1.0, 1.0f, (short) 1, BigInteger.ONE, new BigDecimal("1.00"))) {
                SchemaObject schema = schemaObject().withKeyword(keyword, value).build();
                assertEquals(keyword + " from " + value.getClass(), Integer.valueOf(1),
                    SchemaObject.class.getMethod(keyword).invoke(schema));
                assertEquals(Integer.valueOf(1), SchemaObject.class.getMethod(keyword).invoke(schema.toBuilder().build()));
                assertEquals(1, json(schema).getInt(keyword));
            }
        }
    }

    @Test public void integralKeywordViewsRejectOverflowWithoutLosingTheSchemaValue() throws Exception {
        BigInteger large = new BigInteger("123456789012345678901234567890");
        for (String keyword : Arrays.asList("maxLength", "minLength", "maxItems", "minItems",
            "maxProperties", "minProperties", "minContains", "maxContains")) {
            SchemaObject schema = schemaObject().withKeyword(keyword, large).build();
            assertEquals(large, json(schema).getBigInteger(keyword));
            assertEquals(large, schema.toBuilder().build().keywords().get(keyword));
            java.lang.reflect.InvocationTargetException error = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> SchemaObject.class.getMethod(keyword).invoke(schema));
            assertTrue(error.getCause().toString(), error.getCause() instanceof IllegalStateException);
            assertTrue(error.getCause().getMessage().contains("keywords()"));
        }
    }

    @Test public void presenceAndNullabilityAreIndependent() throws Exception {
        for (boolean required : new boolean[]{false, true}) for (boolean nullable : new boolean[]{false, true}) {
            SchemaObject property = schemaObject().withTypes(nullable ? Arrays.asList("string", "null") : Collections.singletonList("string"))
                .withDefaultValue("fallback").build();
            SchemaObject parent = schemaObject().withType("object").withProperties(Collections.singletonMap("name", property))
                .withRequired(required ? Collections.singletonList("name") : null).build();
            accepts(parent, "{}", !required);
            accepts(parent, "{\"name\":null}", nullable);
            accepts(parent, "{\"name\":\"a\"}", true);
            accepts(parent, "{\"name\":4}", false);
        }
    }

    @Test public void nullabilityDoesNotExpandEnumAndExplicitNullIsPreserved() throws Exception {
        SchemaObject schema = schemaObject().withType("string").withNullable(true)
            .withEnumValue(Arrays.asList("a", "b")).withDefaultValue(JsonNull.INSTANCE)
            .withExamples(Arrays.asList(JsonNull.INSTANCE, "a")).build();
        accepts(schema, "null", false);
        accepts(schema, "\"a\"", true);
        assertTrue(json(schema).has("default"));
        assertTrue(json(schema).isNull("default"));
        assertFalse(json(schema).has("nullable"));
        assertEquals(Arrays.asList("string", "null"), schema.types());
        assertThrows(IllegalStateException.class, schema::type);
        SchemaObject explicit = schema.toBuilder().withEnumValue(Arrays.asList("a", JsonNull.INSTANCE)).build();
        accepts(explicit, "null", true);
        accepts(explicit.toBuilder().withNullable(false).build(), "null", false);
        accepts(schemaObject().withConstValue(JsonNull.INSTANCE).build(), "null", true);
        accepts(schemaObject().withConstValue(JsonNull.INSTANCE).build(), "0", false);
    }

    @Test public void booleansCompositionAndLegacyAdaptersHaveCorrectMeaning() throws Exception {
        accepts(schemaObject().withEnumValue(Collections.emptyList()).build(), "null", false);
        accepts(booleanSchema(true).build(), "null", true);
        accepts(booleanSchema(false).build(), "{}", false);
        SchemaObject string = schemaObject().withType("string").build();
        SchemaObject number = schemaObject().withType("number").build();
        SchemaObject neither = schemaObject().withNot(Arrays.asList(string, number)).build();
        assertTrue(json(neither).get("not") instanceof org.json.JSONObject);
        accepts(neither, "\"a\"", false);
        accepts(neither, "2", false);
        accepts(neither, "true", true);
        assertEquals("{}", schemaObject().withNot(Collections.emptyList()).build().toString());
        assertJsonEquals(json(string), json(schemaObject().withNot(Collections.singletonList(string)).build()).get("not"));
        SchemaObject exclusive = schemaObject().withMinimum(1.0).withExclusiveMinimum(true).build();
        assertFalse(json(exclusive).has("minimum"));
        accepts(exclusive, "1", false);
        accepts(exclusive, "1.1", true);
        assertThrows(IllegalArgumentException.class, () -> schemaObject().withExclusiveMaximum(true).build());
        assertThrows(IllegalArgumentException.class, () -> booleanSchema(true).withType("string").build());
    }

    @Test public void conditionalDependenciesAndUnevaluatedKeywordsValidateInstances() throws Exception {
        SchemaObject string = schemaObject().withType("string").build();
        SchemaObject schema = schemaObject().withType("object")
            .withProperties(Collections.singletonMap("name", string))
            .withIfSchema(schemaObject().withRequired(Collections.singletonList("name")).build())
            .withThenSchema(schemaObject().withProperties(Collections.singletonMap("code", string)).withRequired(Collections.singletonList("code")).build())
            .withElseSchema(schemaObject().withProperties(Collections.singletonMap("anonymous", schemaObject().withConstValue(true).build())).build())
            .withDependentRequired(Collections.singletonMap("name", Collections.singletonList("code")))
            .withUnevaluatedProperties(booleanSchema(false).build()).build();
        accepts(schema, "{\"name\":\"a\",\"code\":\"b\"}", true);
        accepts(schema, "{\"name\":\"a\"}", false);
        accepts(schema, "{\"anonymous\":true}", true);
        accepts(schema, "{\"anonymous\":false}", false);
        accepts(schema, "{\"unknown\":1}", false);
        SchemaObject tuple = schemaObject().withType("array").withPrefixItems(Collections.singletonList(string))
            .withContains(schemaObject().withType("integer").build()).withMinContains(1).withMaxContains(2)
            .withUnevaluatedItems(booleanSchema(false).build()).build();
        accepts(tuple, "[\"a\",1]", true);
        accepts(tuple, "[\"a\"]", false);
        accepts(tuple, "[\"a\",1,2,3]", false);
        accepts(tuple, "[\"a\",1,true]", false);
    }

    @Test public void oneOfAndConstSelectPaymentShapes() throws Exception {
        SchemaObject payment = schemaObject().withType("object")
            .withProperties(Map.of("kind", schemaObject().withType("string").build(),
                "amount", schemaObject().withType("number").withExclusiveMinimumValue(BigDecimal.ZERO).build(),
                "cardToken", schemaObject().withType("string").build(), "iban", schemaObject().withType("string").build()))
            .withRequired(Arrays.asList("kind", "amount"))
            .withOneOf(Arrays.asList(
                schemaObject().withTitle("Card payment").withProperties(Collections.singletonMap("kind",
                    schemaObject().withConstValue("card").build())).withRequired(Collections.singletonList("cardToken")).build(),
                schemaObject().withTitle("Bank transfer").withProperties(Collections.singletonMap("kind",
                    schemaObject().withConstValue("bank").build())).withRequired(Collections.singletonList("iban")).build()))
            .withUnevaluatedProperties(booleanSchema(false).build()).build();
        OfflineOpenApiValidator.object("SchemaObject", json(payment));
        accepts(payment, "{\"kind\":\"card\",\"amount\":19.95,\"cardToken\":\"tok_demo\"}", true);
        accepts(payment, "{\"kind\":\"bank\",\"amount\":42,\"iban\":\"demo\"}", true);
        accepts(payment, "{\"kind\":\"card\",\"amount\":19.95,\"iban\":\"demo\"}", false);
        accepts(payment, "{\"kind\":\"cash\",\"amount\":19.95,\"cardToken\":\"demo\"}", false);
        accepts(payment, "{\"kind\":\"card\",\"amount\":0,\"cardToken\":\"demo\"}", false);
        assertJsonEquals(json(payment), json(payment.toBuilder().build()));
    }

    @Test public void validatorRejectsUnbundledReferencesWithoutNetworkFallback() {
        com.networknt.schema.SchemaException error = assertThrows(com.networknt.schema.SchemaException.class, () ->
            accepts(schemaObject().withRef("https://example.test/unbundled-schema").build(), "{}", true));
        assertTrue(error.getCause() instanceof java.io.FileNotFoundException);
        assertEquals("https://example.test/unbundled-schema", error.getCause().getMessage());
    }

    @Test public void refsAnchorsAndPreciseBoundsAreCopied() throws Exception {
        SchemaObject schema = schemaObject().withId("https://example.test/schema")
            .withDefs(Collections.singletonMap("amount", schemaObject().withType("number")
                .withExclusiveMinimumValue(new BigDecimal("0.12345678901234567890123456789")).build()))
            .withRef("#/$defs/amount").withComment("unchanged").build();
        assertJsonEquals(json(schema), json(schema.toBuilder().build()));
        accepts(schema, "0.12345678901234567890123456789", false);
        accepts(schema, "0.12345678901234567890123456790", true);
        accepts(schema, "0.1", false);
        accepts(schema, "1", true);
        assertTrue(schema.toString().contains("0.12345678901234567890123456789"));
        SchemaObject dynamic = schemaObject().withId("https://example.test/tree").withDynamicAnchor("node")
            .withType("object").withProperties(Collections.singletonMap("child", schemaObject().withDynamicRef("#node").build())).build();
        accepts(dynamic, "{\"child\":{}}", true);
        accepts(dynamic, "{\"child\":1}", false);
        schemaObject().withPatternText("(?<ecma>.*)").build();
    }

    @Test public void jsonValuesAreDeeplyImmutableAndSerializeExactly() throws Exception {
        List<Object> nested = new ArrayList<>(Arrays.asList(1, null));
        Map<String, Object> source = new HashMap<>(); source.put("list", nested);
        SchemaObjectBuilder builder = schemaObject().withDefaultValue(source).withEnumValue(Arrays.asList(1, true, source, new int[]{2, 3}));
        SchemaObject built = builder.build();
        String before = built.toString();
        nested.add(9); source.put("extra", true); builder.withTitle("changed");
        assertEquals(before, built.toString());
        assertEquals(before, built.toBuilder().build().toString());
        assertThrows(UnsupportedOperationException.class, () -> ((Map) built.defaultValue()).put("x", 1));
        assertThrows(UnsupportedOperationException.class, () -> ((List) ((Map) built.defaultValue()).get("list")).add(1));
        assertEquals("[1,2]", jsonValue(new int[]{1, 2}).toString());
        assertEquals("[1,2]", jsonValue(new LinkedHashSet<>(Arrays.asList(1, 2))).toString());
        assertEquals("123456789012345678901234567890", jsonValue(new BigInteger("123456789012345678901234567890")).toString());
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> schemaObject().withDefaultValue(value).build());
            assertThrows(IllegalArgumentException.class, () -> Jsonizer.writeValue(new StringWriter(), value));
        }
        StringBuilder controls = new StringBuilder(); for (char c = 0; c < 32; c++) controls.append(c);
        assertEquals(controls.toString(), jsonValue(controls.toString()));
    }
}
