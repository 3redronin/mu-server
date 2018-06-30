package io.muserver.openapi;

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <p>The Schema Object allows the definition of input and output data types. These types can be objects, but also
 * primitives and arrays. This object is an extended subset of the <a href="http://json-schema.org/">JSON Schema
 * Specification Wright Draft 00</a>.</p>
 * <p>For more information about the properties, see <a href="https://tools.ietf.org/html/draft-wright-json-schema-00">JSON
 * Schema Core</a> and <a href="https://tools.ietf.org/html/draft-wright-json-schema-validation-00" >JSON Schema Validation</a>.
 * Unless stated otherwise, the property definitions follow the JSON Schema.</p>
 */
public class SchemaObjectBuilder {
    private String title;
    private Double multipleOf;
    private Double maximum;
    private Boolean exclusiveMaximum;
    private Double minimum;
    private Boolean exclusiveMinimum;
    private Integer maxLength;
    private Integer minLength;
    private Pattern pattern;
    private Integer maxItems;
    private Integer minItems;
    private Boolean uniqueItems;
    private Integer maxProperties;
    private Integer minProperties;
    private List<String> required;
    private List<Object> enumValue;
    private String type;
    private List<SchemaObject> allOf;
    private List<SchemaObject> oneOf;
    private List<SchemaObject> anyOf;
    private List<SchemaObject> not;
    private SchemaObject items;
    private Map<String, SchemaObject> properties;
    private Object additionalProperties;
    private String description;
    private String format;
    private Object defaultValue;
    private Boolean nullable;
    private DiscriminatorObject discriminator;
    private Boolean readOnly;
    private Boolean writeOnly;
    private XmlObject xml;
    private ExternalDocumentationObject externalDocs;
    private Object example;
    private Boolean deprecated;

    public SchemaObjectBuilder withTitle(String title) {
        this.title = title;
        return this;
    }

    public SchemaObjectBuilder withMultipleOf(Double multipleOf) {
        this.multipleOf = multipleOf;
        return this;
    }

    public SchemaObjectBuilder withMaximum(Double maximum) {
        this.maximum = maximum;
        return this;
    }

    public SchemaObjectBuilder withExclusiveMaximum(Boolean exclusiveMaximum) {
        this.exclusiveMaximum = exclusiveMaximum;
        return this;
    }

    public SchemaObjectBuilder withMinimum(Double minimum) {
        this.minimum = minimum;
        return this;
    }

    public SchemaObjectBuilder withExclusiveMinimum(Boolean exclusiveMinimum) {
        this.exclusiveMinimum = exclusiveMinimum;
        return this;
    }

    public SchemaObjectBuilder withMaxLength(Integer maxLength) {
        this.maxLength = maxLength;
        return this;
    }

    public SchemaObjectBuilder withMinLength(Integer minLength) {
        this.minLength = minLength;
        return this;
    }

    public SchemaObjectBuilder withPattern(Pattern pattern) {
        this.pattern = pattern;
        return this;
    }

    public SchemaObjectBuilder withMaxItems(Integer maxItems) {
        this.maxItems = maxItems;
        return this;
    }

    public SchemaObjectBuilder withMinItems(Integer minItems) {
        this.minItems = minItems;
        return this;
    }

    public SchemaObjectBuilder withUniqueItems(Boolean uniqueItems) {
        this.uniqueItems = uniqueItems;
        return this;
    }

    public SchemaObjectBuilder withMaxProperties(Integer maxProperties) {
        this.maxProperties = maxProperties;
        return this;
    }

    public SchemaObjectBuilder withMinProperties(Integer minProperties) {
        this.minProperties = minProperties;
        return this;
    }

    public SchemaObjectBuilder withRequired(List<String> required) {
        this.required = required;
        return this;
    }

    public SchemaObjectBuilder withEnumValue(List<Object> enumValue) {
        this.enumValue = enumValue;
        return this;
    }

    public SchemaObjectBuilder withType(String type) {
        this.type = type;
        return this;
    }

    public SchemaObjectBuilder withAllOf(List<SchemaObject> allOf) {
        this.allOf = allOf;
        return this;
    }

    public SchemaObjectBuilder withOneOf(List<SchemaObject> oneOf) {
        this.oneOf = oneOf;
        return this;
    }

    public SchemaObjectBuilder withAnyOf(List<SchemaObject> anyOf) {
        this.anyOf = anyOf;
        return this;
    }

    public SchemaObjectBuilder withNot(List<SchemaObject> not) {
        this.not = not;
        return this;
    }

    public SchemaObjectBuilder withItems(SchemaObject items) {
        this.items = items;
        return this;
    }

    public SchemaObjectBuilder withProperties(Map<String, SchemaObject> properties) {
        this.properties = properties;
        return this;
    }

    public SchemaObjectBuilder withAdditionalProperties(Object additionalProperties) {
        this.additionalProperties = additionalProperties;
        return this;
    }

    public SchemaObjectBuilder withDescription(String description) {
        this.description = description;
        return this;
    }

    public SchemaObjectBuilder withFormat(String format) {
        this.format = format;
        return this;
    }

    public SchemaObjectBuilder withDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    /**
     * @param nullable Allows sending a <code>null</code> value for the defined schema. Default value is <code>false</code>.
     * @return The current builder
     */
    public SchemaObjectBuilder withNullable(Boolean nullable) {
        this.nullable = nullable;
        return this;
    }

    /**
     * @param discriminator Adds support for polymorphism. The discriminator is an object name that is used to differentiate between other schemas which may satisfy the payload description.
     * @return The current builder
     */
    public SchemaObjectBuilder withDiscriminator(DiscriminatorObject discriminator) {
        this.discriminator = discriminator;
        return this;
    }

    /**
     * @param readOnly Relevant only for Schema <code>"properties"</code> definitions. Declares the property as "read only".
     *                 This means that it MAY be sent as part of a response but SHOULD NOT be sent as part of the request.
     *                 If the property is marked as <code>readOnly</code> being <code>true</code> and is in the <code>required</code>
     *                 list, the <code>required</code> will take effect on the response only. A property MUST NOT be marked
     *                 as both <code>readOnly</code> and <code>writeOnly</code> being <code>true</code>. Default value is <code>false</code>.
     * @return The current builder
     */
    public SchemaObjectBuilder withReadOnly(Boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    /**
     * @param writeOnly Relevant only for Schema <code>"properties"</code> definitions. Declares the property as "write only".
     *                  Therefore, it MAY be sent as part of a request but SHOULD NOT be sent as part of the response. If
     *                  the property is marked as <code>writeOnly</code> being <code>true</code> and is in the
     *                  <code>required</code> list, the <code>required</code> will take effect on the request only. A property
     *                  MUST NOT be marked as both <code>readOnly</code> and <code>writeOnly</code> being <code>true</code>.
     *                  Default value is <code>false</code>.
     * @return The current builder
     */
    public SchemaObjectBuilder withWriteOnly(Boolean writeOnly) {
        this.writeOnly = writeOnly;
        return this;
    }

    /**
     * @param xml This MAY be used only on properties schemas. It has no effect on root schemas. Adds additional metadata
     *            to describe the XML representation of this property.
     * @return The current builder
     */
    public SchemaObjectBuilder withXml(XmlObject xml) {
        this.xml = xml;
        return this;
    }

    /**
     * @param externalDocs Additional external documentation for this schema.
     * @return The current builder
     */
    public SchemaObjectBuilder withExternalDocs(ExternalDocumentationObject externalDocs) {
        this.externalDocs = externalDocs;
        return this;
    }

    /**
     * @param example A free-form property to include an example of an instance for this schema. To represent examples
     *                that cannot be naturally represented in JSON or YAML, a string value can be used to contain the
     *                example with escaping where necessary.
     * @return The current builder
     */
    public SchemaObjectBuilder withExample(Object example) {
        this.example = example;
        return this;
    }

    /**
     * @param deprecated Specifies that a schema is deprecated and SHOULD be transitioned out of usage. Default value is <code>false</code>.
     * @return The current builder
     */
    public SchemaObjectBuilder withDeprecated(Boolean deprecated) {
        this.deprecated = deprecated;
        return this;
    }

    public SchemaObject build() {
        return new SchemaObject(title, multipleOf, maximum, exclusiveMaximum, minimum, exclusiveMinimum, maxLength, minLength, pattern, maxItems, minItems, uniqueItems, maxProperties, minProperties, required, enumValue, type, allOf, oneOf, anyOf, not, items, properties, additionalProperties, description, format, defaultValue, nullable, discriminator, readOnly, writeOnly, xml, externalDocs, example, deprecated);
    }

    /**
     * Creates a builder for a {@link SchemaObject}
     *
     * @return A new builder
     */
    public static SchemaObjectBuilder schemaObject() {
        return new SchemaObjectBuilder();
    }

    /**
     * Creates a builder for a {@link SchemaObject} with the type and format based on the given class
     * @param from Type type to build from, e.g. if the type is <code>String.class</code> then the <code>type</code> will
     *             be set as <code>string</code>.
     * @return A new builder
     */
    public static SchemaObjectBuilder schemaObjectFrom(Class<?> from) {
        String jsonType = jsonType(from);
        return schemaObject()
            .withType(jsonType)
            .withFormat(jsonFormat(from))
            .withNullable(!from.isPrimitive())
            .withItems(itemsFor(from, "array".equals(jsonType)));
    }

    private static SchemaObject itemsFor(Class<?> from, boolean isJsonArray) {
        Class<?> componentType = from.getComponentType();
        if (componentType == null) {
            return isJsonArray ? schemaObject().withType("object").build() : null;
        }
        return schemaObjectFrom(componentType).build();
    }

    private static String jsonType(Class<?> type) {
        if (CharSequence.class.isAssignableFrom(type) || type.equals(byte.class) || type.equals(Byte.class) || type.isAssignableFrom(Date.class)) {
            return "string";
        } else if (type.equals(boolean.class) || type.equals(Boolean.class)) {
            return "boolean";
        } else if (type.equals(int.class) || type.equals(Integer.class) || type.equals(long.class) || type.equals(Long.class)) {
            return "integer";
        } else if (Number.class.isAssignableFrom(type) || type.equals(float.class) || type.equals(double.class)) {
            return "number";
        } else if (Collection.class.isAssignableFrom(type) || type.isArray()) {
            return "array";
        }
        return "object";
    }

    private static String jsonFormat(Class<?> type) {
        if (type.equals(int.class) || type.equals(Integer.class)) {
            return "int32";
        } else if (type.equals(long.class) || type.equals(Long.class)) {
            return "int64";
        } else if (type.equals(float.class) || type.equals(Float.class)) {
            return "float";
        } else if (type.equals(double.class) || type.equals(Double.class)) {
            return "double";
        } else if (type.equals(byte.class) || type.equals(Byte.class)) {
            return "byte";
        } else if (type.equals(Date.class)) {
            return "date-time";
        }
        return null;
    }


}