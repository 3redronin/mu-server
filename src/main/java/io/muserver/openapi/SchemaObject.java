package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static io.muserver.openapi.Jsonizer.append;

/**
 * @see SchemaObjectBuilder
 */
public class SchemaObject implements JsonWriter {

    private final String title;
    private final Double multipleOf;
    private final Double maximum;
    private final Boolean exclusiveMaximum;
    private final Double minimum;
    private final Boolean exclusiveMinimum;
    private final Integer maxLength;
    private final Integer minLength;
    private final Pattern pattern;
    private final Integer maxItems;
    private final Integer minItems;
    private final Boolean uniqueItems;
    private final Integer maxProperties;
    private final Integer minProperties;
    private final List<String> required;
    private final List<Object> enumValue;
    private final String type;
    private final List<SchemaObject> allOf;
    private final List<SchemaObject> oneOf;
    private final List<SchemaObject> anyOf;
    private final List<SchemaObject> not;
    private final SchemaObject items;
    private final Map<String, SchemaObject> properties;
    private final Object additionalProperties;
    private final String description;
    private final String format;
    private final Object defaultValue;
    private final Boolean nullable;
    private final DiscriminatorObject discriminator;
    private final Boolean readOnly;
    private final Boolean writeOnly;
    private final XmlObject xml;
    private final ExternalDocumentationObject externalDocs;
    private final Object example;
    private final Boolean deprecated;

    SchemaObject(String title, Double multipleOf, Double maximum, Boolean exclusiveMaximum, Double minimum, Boolean exclusiveMinimum, Integer maxLength, Integer minLength, Pattern pattern, Integer maxItems, Integer minItems, Boolean uniqueItems, Integer maxProperties, Integer minProperties, List<String> required, List<Object> enumValue, String type, List<SchemaObject> allOf, List<SchemaObject> oneOf, List<SchemaObject> anyOf, List<SchemaObject> not, SchemaObject items, Map<String, SchemaObject> properties, Object additionalProperties, String description, String format, Object defaultValue, Boolean nullable, DiscriminatorObject discriminator, Boolean readOnly, Boolean writeOnly, XmlObject xml, ExternalDocumentationObject externalDocs, Object example, Boolean deprecated) {
        if (readOnly != null && readOnly && writeOnly != null && writeOnly) {
            throw new IllegalArgumentException("A schema cannot be both read only and write only");
        }
        if ("array".equals(type) && items == null) {
            throw new IllegalArgumentException("'items' cannot be null when type is 'array'");
        }
        if (defaultValue != null && type != null) {
            Class<?> defaultClass = defaultValue.getClass();
            switch (type) {
                case "number":
                    if (!Number.class.isAssignableFrom(defaultClass)) {
                        throw new IllegalArgumentException("The default value must be a number but was " + defaultClass);
                    }
                    break;
                case "boolean":
                    if (!Boolean.class.isAssignableFrom(defaultClass)) {
                        throw new IllegalArgumentException("The default value must be a boolean but was " + defaultClass);
                    }
                    break;
                case "array":
                    if (!Collection.class.isAssignableFrom(defaultClass) && !defaultClass.isArray()) {
                        throw new IllegalArgumentException("The default value must be a boolean but was " + defaultClass);
                    }
                    break;
            }
        }
        this.title = title;
        this.multipleOf = multipleOf;
        this.maximum = maximum;
        this.exclusiveMaximum = exclusiveMaximum;
        this.minimum = minimum;
        this.exclusiveMinimum = exclusiveMinimum;
        this.maxLength = maxLength;
        this.minLength = minLength;
        this.pattern = pattern;
        this.maxItems = maxItems;
        this.minItems = minItems;
        this.uniqueItems = uniqueItems;
        this.maxProperties = maxProperties;
        this.minProperties = minProperties;
        this.required = required;
        this.enumValue = enumValue;
        this.type = type;
        this.allOf = allOf;
        this.oneOf = oneOf;
        this.anyOf = anyOf;
        this.not = not;
        this.items = items;
        this.properties = properties;
        this.additionalProperties = additionalProperties;
        this.description = description;
        this.format = format;
        this.defaultValue = defaultValue;
        this.nullable = nullable;
        this.discriminator = discriminator;
        this.readOnly = readOnly;
        this.writeOnly = writeOnly;
        this.xml = xml;
        this.externalDocs = externalDocs;
        this.example = example;
        this.deprecated = deprecated;
    }


    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.append('{');
        boolean isFirst = true;
        isFirst = append(writer, "title", title, isFirst);
        isFirst = append(writer, "multipleOf", multipleOf, isFirst);
        isFirst = append(writer, "maximum", maximum, isFirst);
        isFirst = append(writer, "exclusiveMaximum", exclusiveMaximum, isFirst);
        isFirst = append(writer, "minimum", minimum, isFirst);
        isFirst = append(writer, "exclusiveMinimum", exclusiveMinimum, isFirst);
        isFirst = append(writer, "maxLength", maxLength, isFirst);
        isFirst = append(writer, "minLength", minLength, isFirst);
        isFirst = append(writer, "pattern", pattern, isFirst);
        isFirst = append(writer, "maxItems", maxItems, isFirst);
        isFirst = append(writer, "minItems", minItems, isFirst);
        isFirst = append(writer, "uniqueItems", uniqueItems, isFirst);
        isFirst = append(writer, "maxProperties", maxProperties, isFirst);
        isFirst = append(writer, "minProperties", minProperties, isFirst);
        isFirst = append(writer, "required", required, isFirst);
        if (this.enumValue != null) {
            List<String> enums = new ArrayList<>();
            if (nullable != null && nullable) {
                enums.add(null);
            }
            for (Object o : this.enumValue) {
                enums.add(((Enum<? extends Enum<?>>) o).name());
            }
            isFirst = append(writer, "enum", enums, isFirst);
        }
        isFirst = append(writer, "type", type, isFirst);
        isFirst = append(writer, "allOf", allOf, isFirst);
        isFirst = append(writer, "oneOf", oneOf, isFirst);
        isFirst = append(writer, "anyOf", anyOf, isFirst);
        isFirst = append(writer, "not", not, isFirst);
        isFirst = append(writer, "items", items, isFirst);
        isFirst = append(writer, "properties", properties, isFirst);
        isFirst = append(writer, "additionalProperties", additionalProperties, isFirst);
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "format", format, isFirst);
        isFirst = append(writer, "default", defaultValue, isFirst);
        isFirst = append(writer, "nullable", nullable, isFirst);
        isFirst = append(writer, "discriminator", discriminator, isFirst);
        isFirst = append(writer, "readOnly", readOnly, isFirst);
        isFirst = append(writer, "writeOnly", writeOnly, isFirst);
        isFirst = append(writer, "xml", xml, isFirst);
        isFirst = append(writer, "externalDocs", externalDocs, isFirst);
        isFirst = append(writer, "example", example, isFirst);
        isFirst = append(writer, "deprecated", deprecated, isFirst);
        writer.append('}');
    }

    @Override
    public String toString() {
        return "SchemaObject{" +
            "title='" + title + '\'' +
            ", multipleOf=" + multipleOf +
            ", maximum=" + maximum +
            ", exclusiveMaximum=" + exclusiveMaximum +
            ", minimum=" + minimum +
            ", exclusiveMinimum=" + exclusiveMinimum +
            ", maxLength=" + maxLength +
            ", minLength=" + minLength +
            ", pattern=" + pattern +
            ", maxItems=" + maxItems +
            ", minItems=" + minItems +
            ", uniqueItems=" + uniqueItems +
            ", maxProperties=" + maxProperties +
            ", minProperties=" + minProperties +
            ", required=" + required +
            ", enumValue=" + enumValue +
            ", type='" + type + '\'' +
            ", allOf=" + allOf +
            ", oneOf=" + oneOf +
            ", anyOf=" + anyOf +
            ", not=" + not +
            ", items=" + items +
            ", properties=" + properties +
            ", additionalProperties=" + additionalProperties +
            ", description='" + description + '\'' +
            ", format='" + format + '\'' +
            ", defaultValue=" + defaultValue +
            ", nullable=" + nullable +
            ", discriminator=" + discriminator +
            ", readOnly=" + readOnly +
            ", writeOnly=" + writeOnly +
            ", xml=" + xml +
            ", externalDocs=" + externalDocs +
            ", example=" + example +
            ", deprecated=" + deprecated +
            '}';
    }

    /**
     * @return A new builder with the values set based on this instance
     */
    public SchemaObjectBuilder toBuilder() {
        return new SchemaObjectBuilder()
            .withTitle(title)
            .withMultipleOf(multipleOf)
            .withMaximum(maximum)
            .withExclusiveMaximum(exclusiveMaximum)
            .withMinimum(minimum)
            .withExclusiveMinimum(exclusiveMinimum)
            .withMaxLength(maxLength)
            .withMinLength(minLength)
            .withPattern(pattern)
            .withMaxItems(maxItems)
            .withMinItems(minItems)
            .withUniqueItems(uniqueItems)
            .withMaxProperties(maxProperties)
            .withMinProperties(minProperties)
            .withRequired(required)
            .withEnumValue(enumValue)
            .withType(type)
            .withAllOf(allOf)
            .withOneOf(oneOf)
            .withAnyOf(anyOf)
            .withNot(not)
            .withItems(items)
            .withProperties(properties)
            .withAdditionalProperties(additionalProperties)
            .withDescription(description)
            .withFormat(format)
            .withDefaultValue(defaultValue)
            .withNullable(nullable)
            .withDiscriminator(discriminator)
            .withReadOnly(readOnly)
            .withWriteOnly(writeOnly)
            .withXml(xml)
            .withExternalDocs(externalDocs)
            .withExample(example)
            .withDeprecated(deprecated);
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withDeprecated(Boolean)}, unless null was passed in which case this returns false
     */
    public boolean isDeprecated() {
        return deprecated != null && deprecated;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withTitle}
     */
    public String title() {
        return title;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMultipleOf}
     */
    public Double multipleOf() {
        return multipleOf;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMaximum}
     */
    public Double maximum() {
        return maximum;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withExclusiveMaximum}
     */
    public Boolean exclusiveMaximum() {
        return exclusiveMaximum;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMinimum}
     */
    public Double minimum() {
        return minimum;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withExclusiveMinimum}
     */
    public Boolean exclusiveMinimum() {
        return exclusiveMinimum;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMaxLength}
     */
    public Integer maxLength() {
        return maxLength;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMinLength}
     */
    public Integer minLength() {
        return minLength;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withPattern}
     */
    public Pattern pattern() {
        return pattern;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMaxItems}
     */
    public Integer maxItems() {
        return maxItems;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMinItems}
     */
    public Integer minItems() {
        return minItems;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withUniqueItems}
     */
    public Boolean uniqueItems() {
        return uniqueItems;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMaxProperties}
     */
    public Integer maxProperties() {
        return maxProperties;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMinProperties}
     */
    public Integer minProperties() {
        return minProperties;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withRequired}
     */
    public List<String> required() {
        return required;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withEnumValue}
     */
    public List<Object> enumValue() {
        return enumValue;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withType}
     */
    public String type() {
        return type;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withAllOf}
     */
    public List<SchemaObject> allOf() {
        return allOf;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withOneOf}
     */
    public List<SchemaObject> oneOf() {
        return oneOf;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withAnyOf}
     */
    public List<SchemaObject> anyOf() {
        return anyOf;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withNot}
     */
    public List<SchemaObject> not() {
        return not;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withItems}
     */
    public SchemaObject items() {
        return items;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withProperties}
     */
    public Map<String, SchemaObject> properties() {
        return properties;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withAdditionalProperties}
     */
    public Object additionalProperties() {
        return additionalProperties;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withDescription}
     */
    public String description() {
        return description;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withFormat}
     */
    public String format() {
        return format;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withDefaultValue}
     */
    public Object defaultValue() {
        return defaultValue;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withNullable}
     */
    public Boolean nullable() {
        return nullable;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withDiscriminator}
     */
    public DiscriminatorObject discriminator() {
        return discriminator;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withReadOnly}
     */
    public Boolean readOnly() {
        return readOnly;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withWriteOnly}
     */
    public Boolean writeOnly() {
        return writeOnly;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withXml}
     */
    public XmlObject xml() {
        return xml;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withExternalDocs}
     */
    public ExternalDocumentationObject externalDocs() {
        return externalDocs;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withExample}
     */
    public Object example() {
        return example;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withDeprecated}
     */
    public Boolean deprecated() {
        return deprecated;
    }
}
