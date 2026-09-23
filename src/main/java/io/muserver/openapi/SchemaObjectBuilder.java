package io.muserver.openapi;

import org.jspecify.annotations.Nullable;
import java.util.*;
import java.util.regex.Pattern;
import io.muserver.UploadedFile;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.Temporal;
import static java.util.Arrays.asList;

/** Builds OpenAPI schemas using JSON Schema 2020-12 keywords. */
public class SchemaObjectBuilder {
    private final Map<String, Object> keywords = new LinkedHashMap<>();
    private @Nullable Boolean booleanValue;
    private @Nullable Boolean legacyExclusiveMaximum;
    private @Nullable Boolean legacyExclusiveMinimum;
    private @Nullable Boolean legacyNullable;

    /** Creates an unconstrained object schema builder. */
    public SchemaObjectBuilder() { }
    SchemaObjectBuilder(Map<String, Object> keywords, @Nullable Boolean booleanValue) {
        this.keywords.putAll(keywords);
        this.booleanValue = booleanValue;
    }
    private SchemaObjectBuilder set(String keyword, @Nullable Object value) {
        if (value == null) keywords.remove(keyword); else keywords.put(keyword, value);
        return this;
    }
    /** @return a new builder */
    public static SchemaObjectBuilder schemaObject() { return new SchemaObjectBuilder(); }
    /**
     * @param value the boolean schema value, or null for an object schema
     * @return this builder */
    public SchemaObjectBuilder withBooleanValue(@Nullable Boolean value) { this.booleanValue = value; return this; }
    /**
     * @param value whether every instance is accepted
     * @return a boolean schema builder */
    public static SchemaObjectBuilder booleanSchema(boolean value) { return schemaObject().withBooleanValue(value); }
    /**
     * @param keyword a JSON Schema keyword, including vocabulary-specific custom keywords
     *
     * @param value its JSON value; null removes the keyword, JsonNull.INSTANCE sets JSON null
     *
     * @return this builder */
    public SchemaObjectBuilder withKeyword(String keyword, @Nullable Object value) {
        Objects.requireNonNull(keyword, "keyword");
        return set(keyword, value);
    }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public SchemaObjectBuilder withExtension(String name, @Nullable Object value) {
        if (!name.startsWith("x-")) throw new IllegalArgumentException("Extension names must start with x-");
        return set(name, value);
    }
    /** @return an immutable built schema */
    public SchemaObject build() {
        Map<String, Object> values = new LinkedHashMap<>(keywords);
        adaptBound(values, "maximum", "exclusiveMaximum", legacyExclusiveMaximum);
        adaptBound(values, "minimum", "exclusiveMinimum", legacyExclusiveMinimum);
        if (Boolean.TRUE.equals(legacyNullable)) {
            Object type = values.get("type");
            if (type == null) throw new IllegalArgumentException("withNullable requires an explicit type; use withTypes");
            List<String> types = new ArrayList<>(JsonValues.types(type));
            if (!types.contains("null")) types.add("null");
            values.put("type", types);
        }
        if (Boolean.FALSE.equals(legacyNullable) && values.containsKey("type")) {
            List<String> types = new ArrayList<>(JsonValues.types(values.get("type")));
            types.remove("null");
            if (types.isEmpty()) throw new IllegalArgumentException("withNullable(false) cannot remove the only type");
            values.put("type", types.size() == 1 ? types.get(0) : types);
        }
        JsonValues.validateSchema(values);
        return new SchemaObject(values, booleanValue);
    }
    private static void adaptBound(Map<String, Object> values, String bound, String exclusive, @Nullable Boolean flag) {
        if (Boolean.TRUE.equals(flag)) {
            Object value = values.remove(bound);
            if (value == null) throw new IllegalArgumentException(exclusive + " requires " + bound);
            values.put(exclusive, value);
        }
    }
    /** @return the title keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String title() { return (String) keywords.get("title"); }
    /**
     * @param value the title keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withTitle(@Nullable String value) { return set("title", value); }
    /** @return the multipleOf keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Double multipleOf() { return JsonValues.doubleValue(keywords.get("multipleOf")); }
    /**
     * @param value the multipleOf keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withMultipleOf(@Nullable Double value) { return set("multipleOf", value); }
    /** @return the maximum keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Double maximum() { return JsonValues.doubleValue(keywords.get("maximum")); }
    /**
     * @param value the maximum keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withMaximum(@Nullable Double value) { return set("maximum", value); }
    /** @return the minimum keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Double minimum() { return JsonValues.doubleValue(keywords.get("minimum")); }
    /**
     * @param value the minimum keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withMinimum(@Nullable Double value) { return set("minimum", value); }
    /** @return the maxLength keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Integer maxLength() { return (Integer) keywords.get("maxLength"); }
    /**
     * @param value the maxLength keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withMaxLength(@Nullable Integer value) { return set("maxLength", value); }
    /** @return the minLength keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Integer minLength() { return (Integer) keywords.get("minLength"); }
    /**
     * @param value the minLength keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withMinLength(@Nullable Integer value) { return set("minLength", value); }
    /** @return the pattern keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Pattern pattern() { return patternText() == null ? null : Pattern.compile(Objects.requireNonNull(patternText())); }
    /**
     * @param value the pattern keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withPattern(@Nullable Pattern value) { return set("pattern", value == null ? null : value.pattern()); }
    /** @return the maxItems keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Integer maxItems() { return (Integer) keywords.get("maxItems"); }
    /**
     * @param value the maxItems keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withMaxItems(@Nullable Integer value) { return set("maxItems", value); }
    /** @return the minItems keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Integer minItems() { return (Integer) keywords.get("minItems"); }
    /**
     * @param value the minItems keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withMinItems(@Nullable Integer value) { return set("minItems", value); }
    /** @return the uniqueItems keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Boolean uniqueItems() { return (Boolean) keywords.get("uniqueItems"); }
    /**
     * @param value the uniqueItems keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withUniqueItems(@Nullable Boolean value) { return set("uniqueItems", value); }
    /** @return the maxProperties keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Integer maxProperties() { return (Integer) keywords.get("maxProperties"); }
    /**
     * @param value the maxProperties keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withMaxProperties(@Nullable Integer value) { return set("maxProperties", value); }
    /** @return the minProperties keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Integer minProperties() { return (Integer) keywords.get("minProperties"); }
    /**
     * @param value the minProperties keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withMinProperties(@Nullable Integer value) { return set("minProperties", value); }
    /** @return the required keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<String> required() { return (List<String>) keywords.get("required"); }
    /**
     * @param value the required keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withRequired(@Nullable List<String> value) { return set("required", value); }
    /** @return the enum keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<Object> enumValue() { return (List<Object>) keywords.get("enum"); }
    /**
     * @param value the enum keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withEnumValue(@Nullable List<Object> value) { return set("enum", value); }
    /** @return the type keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String type() { return JsonValues.singleType(keywords.get("type")); }
    /**
     * @param value the type keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withType(@Nullable String value) { return set("type", value); }
    /** @return the allOf keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<SchemaObject> allOf() { return (List<SchemaObject>) keywords.get("allOf"); }
    /**
     * @param value the allOf keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withAllOf(@Nullable List<SchemaObject> value) { return set("allOf", value); }
    /** @return the oneOf keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<SchemaObject> oneOf() { return (List<SchemaObject>) keywords.get("oneOf"); }
    /**
     * @param value the oneOf keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withOneOf(@Nullable List<SchemaObject> value) { return set("oneOf", value); }
    /** @return the anyOf keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<SchemaObject> anyOf() { return (List<SchemaObject>) keywords.get("anyOf"); }
    /**
     * @param value the anyOf keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withAnyOf(@Nullable List<SchemaObject> value) { return set("anyOf", value); }
    /** @return the items keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject items() { return (SchemaObject) keywords.get("items"); }
    /**
     * @param value the items keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withItems(@Nullable SchemaObject value) { return set("items", value); }
    /** @return the properties keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Map<String, SchemaObject> properties() { return (Map<String, SchemaObject>) keywords.get("properties"); }
    /**
     * @param value the properties keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withProperties(@Nullable Map<String, SchemaObject> value) { return set("properties", value); }
    /** @return the additionalProperties keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Object additionalProperties() { return (Object) keywords.get("additionalProperties"); }
    /**
     * @param value the additionalProperties keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withAdditionalProperties(@Nullable Object value) { return set("additionalProperties", value); }
    /** @return the description keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String description() { return (String) keywords.get("description"); }
    /**
     * @param value the description keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withDescription(@Nullable String value) { return set("description", value); }
    /** @return the format keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String format() { return (String) keywords.get("format"); }
    /**
     * @param value the format keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withFormat(@Nullable String value) { return set("format", value); }
    /** @return the default keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Object defaultValue() { return (Object) keywords.get("default"); }
    /**
     * @param value the default keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withDefaultValue(@Nullable Object value) { return set("default", value); }
    /** @return the discriminator keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable DiscriminatorObject discriminator() { return (DiscriminatorObject) keywords.get("discriminator"); }
    /**
     * @param value the discriminator keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withDiscriminator(@Nullable DiscriminatorObject value) { return set("discriminator", value); }
    /** @return the readOnly keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Boolean readOnly() { return (Boolean) keywords.get("readOnly"); }
    /**
     * @param value the readOnly keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withReadOnly(@Nullable Boolean value) { return set("readOnly", value); }
    /** @return the writeOnly keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Boolean writeOnly() { return (Boolean) keywords.get("writeOnly"); }
    /**
     * @param value the writeOnly keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withWriteOnly(@Nullable Boolean value) { return set("writeOnly", value); }
    /** @return the xml keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable XmlObject xml() { return (XmlObject) keywords.get("xml"); }
    /**
     * @param value the xml keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withXml(@Nullable XmlObject value) { return set("xml", value); }
    /** @return the externalDocs keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable ExternalDocumentationObject externalDocs() { return (ExternalDocumentationObject) keywords.get("externalDocs"); }
    /**
     * @param value the externalDocs keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withExternalDocs(@Nullable ExternalDocumentationObject value) { return set("externalDocs", value); }
    /** @return the example keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Object example() { return (Object) keywords.get("example"); }
    /**
     * @param value the example keyword; null removes it
     * @return this builder
     * @deprecated Use withExamples for JSON Schema examples. */
    @Deprecated
    public SchemaObjectBuilder withExample(@Nullable Object value) { return set("example", value); }
    /** @return the deprecated keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Boolean deprecated() { return (Boolean) keywords.get("deprecated"); }
    /**
     * @param value the deprecated keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withDeprecated(@Nullable Boolean value) { return set("deprecated", value); }
    /** @return the $ref keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String ref() { return (String) keywords.get("$ref"); }
    /**
     * @param value the $ref keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withRef(@Nullable String value) { return set("$ref", value); }
    /** @return the $id keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String id() { return (String) keywords.get("$id"); }
    /**
     * @param value the $id keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withId(@Nullable String value) { return set("$id", value); }
    /** @return the $anchor keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String anchor() { return (String) keywords.get("$anchor"); }
    /**
     * @param value the $anchor keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withAnchor(@Nullable String value) { return set("$anchor", value); }
    /** @return the $dynamicRef keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String dynamicRef() { return (String) keywords.get("$dynamicRef"); }
    /**
     * @param value the $dynamicRef keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withDynamicRef(@Nullable String value) { return set("$dynamicRef", value); }
    /** @return the $dynamicAnchor keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String dynamicAnchor() { return (String) keywords.get("$dynamicAnchor"); }
    /**
     * @param value the $dynamicAnchor keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withDynamicAnchor(@Nullable String value) { return set("$dynamicAnchor", value); }
    /** @return the $schema keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String schemaDialect() { return (String) keywords.get("$schema"); }
    /**
     * @param value the $schema keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withSchemaDialect(@Nullable String value) { return set("$schema", value); }
    /** @return the $vocabulary keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Map<String, Boolean> vocabulary() { return (Map<String, Boolean>) keywords.get("$vocabulary"); }
    /**
     * @param value the $vocabulary keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withVocabulary(@Nullable Map<String, Boolean> value) { return set("$vocabulary", value); }
    /** @return the $defs keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Map<String, SchemaObject> defs() { return (Map<String, SchemaObject>) keywords.get("$defs"); }
    /**
     * @param value the $defs keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withDefs(@Nullable Map<String, SchemaObject> value) { return set("$defs", value); }
    /** @return the $comment keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String comment() { return (String) keywords.get("$comment"); }
    /**
     * @param value the $comment keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withComment(@Nullable String value) { return set("$comment", value); }
    /** @return the type keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<String> types() { return keywords.containsKey("type") ? JsonValues.types(keywords.get("type")) : null; }
    /**
     * @param value the type keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withTypes(@Nullable List<String> value) { return set("type", value); }
    /** @return the const keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Object constValue() { return (Object) keywords.get("const"); }
    /**
     * @param value the const keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withConstValue(@Nullable Object value) { return set("const", value); }
    /** @return the examples keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<Object> examples() { return (List<Object>) keywords.get("examples"); }
    /**
     * @param value the examples keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withExamples(@Nullable List<Object> value) { return set("examples", value); }
    /** @return the not keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject notSchema() { return (SchemaObject) keywords.get("not"); }
    /**
     * @param value the not keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withNotSchema(@Nullable SchemaObject value) { return set("not", value); }
    /** @return the pattern keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String patternText() { return (String) keywords.get("pattern"); }
    /**
     * @param value the pattern keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withPatternText(@Nullable String value) { return set("pattern", value); }
    /** @return the multipleOf keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Number multipleOfNumber() { return (Number) keywords.get("multipleOf"); }
    /**
     * @param value the multipleOf keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withMultipleOfNumber(@Nullable Number value) { return set("multipleOf", value); }
    /** @return the maximum keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Number maximumNumber() { return (Number) keywords.get("maximum"); }
    /**
     * @param value the maximum keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withMaximumNumber(@Nullable Number value) { return set("maximum", value); }
    /** @return the minimum keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Number minimumNumber() { return (Number) keywords.get("minimum"); }
    /**
     * @param value the minimum keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withMinimumNumber(@Nullable Number value) { return set("minimum", value); }
    /** @return the exclusiveMaximum keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Number exclusiveMaximumValue() { return (Number) keywords.get("exclusiveMaximum"); }
    /**
     * @param value the exclusiveMaximum keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withExclusiveMaximumValue(@Nullable Number value) { return set("exclusiveMaximum", value); }
    /** @return the exclusiveMinimum keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Number exclusiveMinimumValue() { return (Number) keywords.get("exclusiveMinimum"); }
    /**
     * @param value the exclusiveMinimum keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withExclusiveMinimumValue(@Nullable Number value) { return set("exclusiveMinimum", value); }
    /** @return the prefixItems keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<SchemaObject> prefixItems() { return (List<SchemaObject>) keywords.get("prefixItems"); }
    /**
     * @param value the prefixItems keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withPrefixItems(@Nullable List<SchemaObject> value) { return set("prefixItems", value); }
    /** @return the contains keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject contains() { return (SchemaObject) keywords.get("contains"); }
    /**
     * @param value the contains keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withContains(@Nullable SchemaObject value) { return set("contains", value); }
    /** @return the minContains keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Integer minContains() { return (Integer) keywords.get("minContains"); }
    /**
     * @param value the minContains keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withMinContains(@Nullable Integer value) { return set("minContains", value); }
    /** @return the maxContains keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Integer maxContains() { return (Integer) keywords.get("maxContains"); }
    /**
     * @param value the maxContains keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withMaxContains(@Nullable Integer value) { return set("maxContains", value); }
    /** @return the patternProperties keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Map<String, SchemaObject> patternProperties() { return (Map<String, SchemaObject>) keywords.get("patternProperties"); }
    /**
     * @param value the patternProperties keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withPatternProperties(@Nullable Map<String, SchemaObject> value) { return set("patternProperties", value); }
    /** @return the propertyNames keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject propertyNames() { return (SchemaObject) keywords.get("propertyNames"); }
    /**
     * @param value the propertyNames keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withPropertyNames(@Nullable SchemaObject value) { return set("propertyNames", value); }
    /** @return the dependentSchemas keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Map<String, SchemaObject> dependentSchemas() { return (Map<String, SchemaObject>) keywords.get("dependentSchemas"); }
    /**
     * @param value the dependentSchemas keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withDependentSchemas(@Nullable Map<String, SchemaObject> value) { return set("dependentSchemas", value); }
    /** @return the dependentRequired keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Map<String, List<String>> dependentRequired() { return (Map<String, List<String>>) keywords.get("dependentRequired"); }
    /**
     * @param value the dependentRequired keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withDependentRequired(@Nullable Map<String, List<String>> value) { return set("dependentRequired", value); }
    /** @return the if keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject ifSchema() { return (SchemaObject) keywords.get("if"); }
    /**
     * @param value the if keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withIfSchema(@Nullable SchemaObject value) { return set("if", value); }
    /** @return the then keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject thenSchema() { return (SchemaObject) keywords.get("then"); }
    /**
     * @param value the then keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withThenSchema(@Nullable SchemaObject value) { return set("then", value); }
    /** @return the else keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject elseSchema() { return (SchemaObject) keywords.get("else"); }
    /**
     * @param value the else keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withElseSchema(@Nullable SchemaObject value) { return set("else", value); }
    /** @return the unevaluatedItems keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject unevaluatedItems() { return (SchemaObject) keywords.get("unevaluatedItems"); }
    /**
     * @param value the unevaluatedItems keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withUnevaluatedItems(@Nullable SchemaObject value) { return set("unevaluatedItems", value); }
    /** @return the unevaluatedProperties keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject unevaluatedProperties() { return (SchemaObject) keywords.get("unevaluatedProperties"); }
    /**
     * @param value the unevaluatedProperties keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withUnevaluatedProperties(@Nullable SchemaObject value) { return set("unevaluatedProperties", value); }
    /** @return the contentEncoding keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String contentEncoding() { return (String) keywords.get("contentEncoding"); }
    /**
     * @param value the contentEncoding keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withContentEncoding(@Nullable String value) { return set("contentEncoding", value); }
    /** @return the contentMediaType keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String contentMediaType() { return (String) keywords.get("contentMediaType"); }
    /**
     * @param value the contentMediaType keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withContentMediaType(@Nullable String value) { return set("contentMediaType", value); }
    /** @return the contentSchema keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject contentSchema() { return (SchemaObject) keywords.get("contentSchema"); }
    /**
     * @param value the contentSchema keyword; null removes it
     * @return this builder */
    public SchemaObjectBuilder withContentSchema(@Nullable SchemaObject value) { return set("contentSchema", value); }
    /**
     * @return the legacy exclusivity flag
     * @deprecated Use exclusiveMaximumValue(). */
    @Deprecated public @Nullable Boolean exclusiveMaximum() { return legacyExclusiveMaximum; }
    /**
     * @param value whether the associated bound is exclusive
     * @return this builder
     *
     * @deprecated Use withExclusiveMaximumValue(Number). */
    @Deprecated public SchemaObjectBuilder withExclusiveMaximum(@Nullable Boolean value) { legacyExclusiveMaximum = value; return this; }
    /**
     * @return the legacy exclusivity flag
     * @deprecated Use exclusiveMinimumValue(). */
    @Deprecated public @Nullable Boolean exclusiveMinimum() { return legacyExclusiveMinimum; }
    /**
     * @param value whether the associated bound is exclusive
     * @return this builder
     *
     * @deprecated Use withExclusiveMinimumValue(Number). */
    @Deprecated public SchemaObjectBuilder withExclusiveMinimum(@Nullable Boolean value) { legacyExclusiveMinimum = value; return this; }
    /**
     * @return the legacy nullable flag
     * @deprecated Use types(). */
    @Deprecated public @Nullable Boolean nullable() { return legacyNullable; }
    /**
     * @param value whether an explicitly typed schema also admits null
     * @return this builder
     *
     * @deprecated Use withTypes(Arrays.asList("string", "null")). Does not change enum or required. */
    @Deprecated public SchemaObjectBuilder withNullable(@Nullable Boolean value) { legacyNullable = value; return this; }
    /**
     * @return a singleton view of not
     * @deprecated Use notSchema(). */
    @Deprecated public @Nullable List<SchemaObject> not() { return notSchema() == null ? null : Collections.singletonList(Objects.requireNonNull(notSchema())); }
    /**
     * @param value excluded schemas; multiple entries become not-anyOf, empty means no restriction
     *
     * @return this builder
     * @deprecated Use withNotSchema(SchemaObject). */
    @Deprecated public SchemaObjectBuilder withNot(@Nullable List<SchemaObject> value) {
        return withNotSchema(value == null || value.isEmpty() ? null : value.size() == 1 ? value.get(0) : schemaObject().withAnyOf(value).build());
    }
    /**
     * Creates a builder for a {@link SchemaObject} with the type and format based on the given class
     *
     * @param from Type type to build from, e.g. if the type is <code>String.class</code> then the <code>type</code> will
     *             be set as <code>string</code>.
     *
     * @return A new builder
     */
    public static SchemaObjectBuilder schemaObjectFrom(Class<?> from) {
        return schemaObjectFrom(from, null);
    }

    /**
     * Creates a builder for a {@link SchemaObject} with the type and format based on the given class and generic type.
     *
     * @param from Type type to build from, e.g. if the type is <code>List.class</code> then the <code>type</code> will
     *             be set as <code>array</code>.
     *
     * @param parameterizedType The generic type of the class, e.g. a String if the type is <code>List&lt;String&gt;</code>
     *
     * @param required True if it's a required value
     *
     * @return A new builder
     */
    @Deprecated
    public static SchemaObjectBuilder schemaObjectFrom(Class<?> from, @Nullable Type parameterizedType, boolean required) {
        return schemaObjectFrom(from, parameterizedType);
    }

    /** Infers known Java scalar and container types without inferring presence or nullability.
     *
     * @param from the raw type
     * @param parameterizedType its resolved generic type
     * @return a schema builder */
    public static SchemaObjectBuilder schemaObjectFrom(Class<?> from, @Nullable Type parameterizedType) {
        Objects.requireNonNull(from, "from");
        if (from.equals(void.class) || from.equals(Void.class)) {
            return schemaObject();
        }
        parameterizedType = getUpperBound(parameterizedType);
        if (isBinaryClass(from)) return schemaObject();
        if (Map.class.isAssignableFrom(from)) {
            SchemaObjectBuilder map = schemaObject().withType("object");
            if (parameterizedType instanceof ParameterizedType) {
                Type[] args = ((ParameterizedType) parameterizedType).getActualTypeArguments();
                if (args.length == 2) map.withAdditionalProperties(schemaObjectFrom(args[1]).build());
            }
            return map;
        }
        String jsonType = jsonType(from);
        SchemaObjectBuilder schemaObjectBuilder = schemaObject()
            .withType(jsonType)
            .withFormat(jsonFormat(from))
            .withExamples(example(from) == null ? null : Collections.singletonList(example(from)))
            .withItems(itemsFor(from, parameterizedType, "array".equals(jsonType)));
        if (from.isEnum()) {
            schemaObjectBuilder.withEnumValue(asList(from.getEnumConstants()));
        }
        return schemaObjectBuilder;
    }

    /**
     * @param type a resolved Java type
     * @return a schema for known types, otherwise an unconstrained schema */
    public static SchemaObjectBuilder schemaObjectFrom(Type type) {
        type = Objects.requireNonNull(getUpperBound(type));
        if (type instanceof Class<?>) return schemaObjectFrom((Class<?>) type);
        if (type instanceof ParameterizedType && ((ParameterizedType) type).getRawType() instanceof Class<?>) {
            return schemaObjectFrom((Class<?>) ((ParameterizedType) type).getRawType(), type);
        }
        if (type instanceof GenericArrayType) {
            return schemaObject().withType("array").withItems(schemaObjectFrom(((GenericArrayType) type).getGenericComponentType()).build());
        }
        return schemaObject();
    }

    private static @Nullable Object example(Class<?> clazz) {
        if (clazz.equals(UUID.class)) return "93d35de9-0083-4765-8b60-822258e8ffad";
        if (clazz.equals(LocalDate.class)) return "2021-02-12";
        if (clazz.equals(Instant.class) || clazz.equals(Date.class)) return "2021-02-12T15:33:28Z";
        return null;
    }

    private static @Nullable Type getUpperBound(@Nullable Type parameterizedType) {
        if (parameterizedType instanceof WildcardType && ((WildcardType)parameterizedType).getUpperBounds().length > 0) {
            parameterizedType = ((WildcardType)parameterizedType).getUpperBounds()[0];
        }
        return parameterizedType;
    }

    private static @Nullable SchemaObject itemsFor(Class<?> from, @Nullable Type parameterizedType, boolean isJsonArray) {
        Class<?> componentType = from.getComponentType();
        if (componentType == null) {
            if (isJsonArray) {
                SchemaObjectBuilder schemaObjectBuilder = schemaObject();
                if (parameterizedType instanceof ParameterizedType) {
                    Type[] actualTypeArguments = ((ParameterizedType) parameterizedType).getActualTypeArguments();
                    if (actualTypeArguments.length == 1) {
                        Type argType = getUpperBound(actualTypeArguments[0]);
                        if (argType != null) schemaObjectBuilder = schemaObjectFrom(argType);
                    }
                }
                return schemaObjectBuilder.build();
            } else {
                return null;
            }
        }
        return schemaObjectFrom(parameterizedType instanceof GenericArrayType
            ? ((GenericArrayType) parameterizedType).getGenericComponentType() : componentType).build();
    }

    private static @Nullable String jsonType(Class<?> type) {
        if (CharSequence.class.isAssignableFrom(type) || type.equals(char.class) || type.equals(Character.class)
            || Date.class.isAssignableFrom(type) || Temporal.class.isAssignableFrom(type) || isBinaryClass(type)
            || UUID.class.isAssignableFrom(type) || type == java.net.URI.class || type == java.net.URL.class || type.isEnum()) {
            return "string";
        } else if (type.equals(boolean.class) || type.equals(Boolean.class)) {
            return "boolean";
        } else if (type.equals(byte.class) || type.equals(Byte.class) || type.equals(short.class) || type.equals(Short.class) || type.equals(java.math.BigInteger.class) || type.equals(int.class) || type.equals(Integer.class) || type.equals(long.class) || type.equals(Long.class)) {
            return "integer";
        } else if (Number.class.isAssignableFrom(type) || type.equals(float.class) || type.equals(double.class)) {
            return "number";
        } else if (Collection.class.isAssignableFrom(type) || type.isArray()) {
            return "array";
        }
        return null;
    }

    // General formats for both inputs and outputs. Input-only temporal conventions live in JavaValueSchemas.
    private static @Nullable String jsonFormat(Class<?> type) {
        if (type == byte.class || type == Byte.class) {
            return "int8";
        } else if (type == short.class || type == Short.class) {
            return "int16";
        } else if (type == char.class || type == Character.class) {
            return "char";
        } else if (type == java.math.BigDecimal.class) {
            return "decimal";
        } else if (type.equals(int.class) || type.equals(Integer.class)) {
            return "int32";
        } else if (type.equals(long.class) || type.equals(Long.class)) {
            return "int64";
        } else if (type.equals(float.class) || type.equals(Float.class)) {
            return "float";
        } else if (type.equals(double.class) || type.equals(Double.class)) {
            return "double";

        } else if (type.equals(Date.class) || type.equals(Instant.class)) {
            return "date-time";
        } else if (type.equals(LocalDate.class)) {
            return "date";
        } else if (isBinaryClass(type)) {
            return "binary";
        } else if (type.equals(UUID.class)) {
            return "uuid";
        } else if (type.equals(java.net.URI.class)) {
            return "uri-reference";
        } else if (type.equals(java.net.URL.class)) {
            return "uri";
        }
        return null;
    }

    private static boolean isBinaryClass(Class<?> type) {
        return UploadedFile.class.isAssignableFrom(type) || File.class.isAssignableFrom(type)
            || InputStream.class.isAssignableFrom(type) || (type.isArray() && type.getComponentType().equals(byte.class));
    }

}
