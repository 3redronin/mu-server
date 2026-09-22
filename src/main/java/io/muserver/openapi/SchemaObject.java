package io.muserver.openapi;

import org.jspecify.annotations.Nullable;
import java.util.*;
import java.util.regex.Pattern;
import java.io.IOException;
import java.io.Writer;

/** An immutable OpenAPI Schema Object, including JSON Schema 2020-12 boolean schemas. */
public class SchemaObject implements JsonWriter {
    private final Map<String, Object> keywords;
    private final @Nullable Boolean booleanValue;

    SchemaObject(Map<String, Object> keywords, @Nullable Boolean booleanValue) {
        if (booleanValue != null && !keywords.isEmpty()) {
            throw new IllegalArgumentException("A boolean schema cannot also contain keywords");
        }
        this.keywords = JsonValues.freezeMap(keywords);
        this.booleanValue = booleanValue;
    }

    /** @return the immutable schema keyword map */
    public Map<String, Object> keywords() { return keywords; }
    /** @return the boolean schema value, or null for an object schema */
    public @Nullable Boolean booleanValue() { return booleanValue; }
    /** @return a builder containing every keyword and the boolean value */
    public SchemaObjectBuilder toBuilder() { return new SchemaObjectBuilder(keywords, booleanValue); }
    @Override public void writeJson(Writer writer) throws IOException {
        if (booleanValue != null) writer.write(booleanValue.toString());
        else Jsonizer.writeObject(writer, keywords);
    }
    @Override public String toString() {
        java.io.StringWriter writer = new java.io.StringWriter();
        try { writeJson(writer); } catch (IOException e) { throw new IllegalStateException(e); }
        return writer.toString();
    }
    /** @return whether this schema is deprecated */
    public boolean isDeprecated() { return Boolean.TRUE.equals(deprecated()); }
    /** @return the title keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String title() { return (String) keywords.get("title"); }
    /** @return the multipleOf keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Double multipleOf() { return JsonValues.doubleValue(keywords.get("multipleOf")); }
    /** @return the maximum keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Double maximum() { return JsonValues.doubleValue(keywords.get("maximum")); }
    /** @return the minimum keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Double minimum() { return JsonValues.doubleValue(keywords.get("minimum")); }
    /** @return the maxLength keyword, or null if absent
     * @throws IllegalStateException if the value exceeds the Integer range; use {@link #keywords()} for the exact value */
    @SuppressWarnings("unchecked")
    public @Nullable Integer maxLength() { return JsonValues.integerValue(keywords.get("maxLength"), "maxLength"); }
    /** @return the minLength keyword, or null if absent
     * @throws IllegalStateException if the value exceeds the Integer range; use {@link #keywords()} for the exact value */
    @SuppressWarnings("unchecked")
    public @Nullable Integer minLength() { return JsonValues.integerValue(keywords.get("minLength"), "minLength"); }
    /** @return the pattern keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Pattern pattern() { return patternText() == null ? null : Pattern.compile(Objects.requireNonNull(patternText())); }
    /** @return the maxItems keyword, or null if absent
     * @throws IllegalStateException if the value exceeds the Integer range; use {@link #keywords()} for the exact value */
    @SuppressWarnings("unchecked")
    public @Nullable Integer maxItems() { return JsonValues.integerValue(keywords.get("maxItems"), "maxItems"); }
    /** @return the minItems keyword, or null if absent
     * @throws IllegalStateException if the value exceeds the Integer range; use {@link #keywords()} for the exact value */
    @SuppressWarnings("unchecked")
    public @Nullable Integer minItems() { return JsonValues.integerValue(keywords.get("minItems"), "minItems"); }
    /** @return the uniqueItems keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Boolean uniqueItems() { return (Boolean) keywords.get("uniqueItems"); }
    /** @return the maxProperties keyword, or null if absent
     * @throws IllegalStateException if the value exceeds the Integer range; use {@link #keywords()} for the exact value */
    @SuppressWarnings("unchecked")
    public @Nullable Integer maxProperties() { return JsonValues.integerValue(keywords.get("maxProperties"), "maxProperties"); }
    /** @return the minProperties keyword, or null if absent
     * @throws IllegalStateException if the value exceeds the Integer range; use {@link #keywords()} for the exact value */
    @SuppressWarnings("unchecked")
    public @Nullable Integer minProperties() { return JsonValues.integerValue(keywords.get("minProperties"), "minProperties"); }
    /** @return the required keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<String> required() { return (List<String>) keywords.get("required"); }
    /** @return the enum keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<Object> enumValue() { return (List<Object>) keywords.get("enum"); }
    /** @return the type keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String type() { return JsonValues.singleType(keywords.get("type")); }
    /** @return the allOf keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<SchemaObject> allOf() { return (List<SchemaObject>) keywords.get("allOf"); }
    /** @return the oneOf keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<SchemaObject> oneOf() { return (List<SchemaObject>) keywords.get("oneOf"); }
    /** @return the anyOf keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<SchemaObject> anyOf() { return (List<SchemaObject>) keywords.get("anyOf"); }
    /** @return the items keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject items() { return (SchemaObject) keywords.get("items"); }
    /** @return the properties keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Map<String, SchemaObject> properties() { return (Map<String, SchemaObject>) keywords.get("properties"); }
    /** @return the additionalProperties keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Object additionalProperties() { return (Object) keywords.get("additionalProperties"); }
    /** @return the description keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String description() { return (String) keywords.get("description"); }
    /** @return the format keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String format() { return (String) keywords.get("format"); }
    /** @return the default keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Object defaultValue() { return (Object) keywords.get("default"); }
    /** @return the discriminator keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable DiscriminatorObject discriminator() { return (DiscriminatorObject) keywords.get("discriminator"); }
    /** @return the readOnly keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Boolean readOnly() { return (Boolean) keywords.get("readOnly"); }
    /** @return the writeOnly keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Boolean writeOnly() { return (Boolean) keywords.get("writeOnly"); }
    /** @return the xml keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable XmlObject xml() { return (XmlObject) keywords.get("xml"); }
    /** @return the externalDocs keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable ExternalDocumentationObject externalDocs() { return (ExternalDocumentationObject) keywords.get("externalDocs"); }
    /** @return the example keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Object example() { return (Object) keywords.get("example"); }
    /** @return the deprecated keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Boolean deprecated() { return (Boolean) keywords.get("deprecated"); }
    /** @return the $ref keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String ref() { return (String) keywords.get("$ref"); }
    /** @return the $id keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String id() { return (String) keywords.get("$id"); }
    /** @return the $anchor keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String anchor() { return (String) keywords.get("$anchor"); }
    /** @return the $dynamicRef keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String dynamicRef() { return (String) keywords.get("$dynamicRef"); }
    /** @return the $dynamicAnchor keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String dynamicAnchor() { return (String) keywords.get("$dynamicAnchor"); }
    /** @return the $schema keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String schemaDialect() { return (String) keywords.get("$schema"); }
    /** @return the $vocabulary keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Map<String, Boolean> vocabulary() { return (Map<String, Boolean>) keywords.get("$vocabulary"); }
    /** @return the $defs keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Map<String, SchemaObject> defs() { return (Map<String, SchemaObject>) keywords.get("$defs"); }
    /** @return the $comment keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String comment() { return (String) keywords.get("$comment"); }
    /** @return the type keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<String> types() { return keywords.containsKey("type") ? JsonValues.types(keywords.get("type")) : null; }
    /** @return the const keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Object constValue() { return (Object) keywords.get("const"); }
    /** @return the examples keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<Object> examples() { return (List<Object>) keywords.get("examples"); }
    /** @return the not keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject notSchema() { return (SchemaObject) keywords.get("not"); }
    /** @return the pattern keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String patternText() { return (String) keywords.get("pattern"); }
    /** @return the multipleOf keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Number multipleOfNumber() { return (Number) keywords.get("multipleOf"); }
    /** @return the maximum keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Number maximumNumber() { return (Number) keywords.get("maximum"); }
    /** @return the minimum keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Number minimumNumber() { return (Number) keywords.get("minimum"); }
    /** @return the exclusiveMaximum keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Number exclusiveMaximumValue() { return (Number) keywords.get("exclusiveMaximum"); }
    /** @return the exclusiveMinimum keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Number exclusiveMinimumValue() { return (Number) keywords.get("exclusiveMinimum"); }
    /** @return the prefixItems keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable List<SchemaObject> prefixItems() { return (List<SchemaObject>) keywords.get("prefixItems"); }
    /** @return the contains keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject contains() { return (SchemaObject) keywords.get("contains"); }
    /** @return the minContains keyword, or null if absent
     * @throws IllegalStateException if the value exceeds the Integer range; use {@link #keywords()} for the exact value */
    @SuppressWarnings("unchecked")
    public @Nullable Integer minContains() { return JsonValues.integerValue(keywords.get("minContains"), "minContains"); }
    /** @return the maxContains keyword, or null if absent
     * @throws IllegalStateException if the value exceeds the Integer range; use {@link #keywords()} for the exact value */
    @SuppressWarnings("unchecked")
    public @Nullable Integer maxContains() { return JsonValues.integerValue(keywords.get("maxContains"), "maxContains"); }
    /** @return the patternProperties keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Map<String, SchemaObject> patternProperties() { return (Map<String, SchemaObject>) keywords.get("patternProperties"); }
    /** @return the propertyNames keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject propertyNames() { return (SchemaObject) keywords.get("propertyNames"); }
    /** @return the dependentSchemas keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Map<String, SchemaObject> dependentSchemas() { return (Map<String, SchemaObject>) keywords.get("dependentSchemas"); }
    /** @return the dependentRequired keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable Map<String, List<String>> dependentRequired() { return (Map<String, List<String>>) keywords.get("dependentRequired"); }
    /** @return the if keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject ifSchema() { return (SchemaObject) keywords.get("if"); }
    /** @return the then keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject thenSchema() { return (SchemaObject) keywords.get("then"); }
    /** @return the else keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject elseSchema() { return (SchemaObject) keywords.get("else"); }
    /** @return the unevaluatedItems keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject unevaluatedItems() { return (SchemaObject) keywords.get("unevaluatedItems"); }
    /** @return the unevaluatedProperties keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject unevaluatedProperties() { return (SchemaObject) keywords.get("unevaluatedProperties"); }
    /** @return the contentEncoding keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String contentEncoding() { return (String) keywords.get("contentEncoding"); }
    /** @return the contentMediaType keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable String contentMediaType() { return (String) keywords.get("contentMediaType"); }
    /** @return the contentSchema keyword, or null if absent */
    @SuppressWarnings("unchecked")
    public @Nullable SchemaObject contentSchema() { return (SchemaObject) keywords.get("contentSchema"); }
    /**
     * @return whether a numeric exclusive bound is present
     * @deprecated Use exclusiveMaximumValue(). */
    @Deprecated public @Nullable Boolean exclusiveMaximum() { return keywords.containsKey("exclusiveMaximum") ? true : null; }
    /**
     * @return whether a numeric exclusive bound is present
     * @deprecated Use exclusiveMinimumValue(). */
    @Deprecated public @Nullable Boolean exclusiveMinimum() { return keywords.containsKey("exclusiveMinimum") ? true : null; }
    /**
     * @return whether type explicitly admits null
     * @deprecated Use types(). */
    @Deprecated public @Nullable Boolean nullable() { return types() == null ? null : Objects.requireNonNull(types()).contains("null"); }
    /**
     * @return a singleton view of not
     * @deprecated Use notSchema(). */
    @Deprecated public @Nullable List<SchemaObject> not() { return notSchema() == null ? null : Collections.singletonList(Objects.requireNonNull(notSchema())); }
}
