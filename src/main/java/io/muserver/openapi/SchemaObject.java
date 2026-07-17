package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

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

    private final @Nullable String title;
    private final @Nullable Double multipleOf;
    private final @Nullable Double maximum;
    private final @Nullable Boolean exclusiveMaximum;
    private final @Nullable Double minimum;
    private final @Nullable Boolean exclusiveMinimum;
    private final @Nullable Integer maxLength;
    private final @Nullable Integer minLength;
    private final @Nullable Pattern pattern;
    private final @Nullable Integer maxItems;
    private final @Nullable Integer minItems;
    private final @Nullable Boolean uniqueItems;
    private final @Nullable Integer maxProperties;
    private final @Nullable Integer minProperties;
    private final @Nullable List<String> required;
    private final @Nullable List<Object> enumValue;
    private final @Nullable String type;
    private final @Nullable List<SchemaObject> allOf;
    private final @Nullable List<SchemaObject> oneOf;
    private final @Nullable List<SchemaObject> anyOf;
    private final @Nullable List<SchemaObject> not;
    private final @Nullable SchemaObject items;
    private final @Nullable Map<String, SchemaObject> properties;
    private final @Nullable Object additionalProperties;
    private final @Nullable String description;
    private final @Nullable String format;
    private final @Nullable Object defaultValue;
    private final @Nullable Boolean nullable;
    private final @Nullable DiscriminatorObject discriminator;
    private final @Nullable Boolean readOnly;
    private final @Nullable Boolean writeOnly;
    private final @Nullable XmlObject xml;
    private final @Nullable ExternalDocumentationObject externalDocs;
    private final @Nullable Object example;
    private final @Nullable Boolean deprecated;

    SchemaObject(@Nullable String title, @Nullable Double multipleOf, @Nullable Double maximum, @Nullable Boolean exclusiveMaximum, @Nullable Double minimum, @Nullable Boolean exclusiveMinimum, @Nullable Integer maxLength, @Nullable Integer minLength, @Nullable Pattern pattern, @Nullable Integer maxItems, @Nullable Integer minItems, @Nullable Boolean uniqueItems, @Nullable Integer maxProperties, @Nullable Integer minProperties, @Nullable List<String> required, @Nullable List<Object> enumValue, @Nullable String type, @Nullable List<SchemaObject> allOf, @Nullable List<SchemaObject> oneOf, @Nullable List<SchemaObject> anyOf, @Nullable List<SchemaObject> not, @Nullable SchemaObject items, @Nullable Map<String, SchemaObject> properties, @Nullable Object additionalProperties, @Nullable String description, @Nullable String format, @Nullable Object defaultValue, @Nullable Boolean nullable, @Nullable DiscriminatorObject discriminator, @Nullable Boolean readOnly, @Nullable Boolean writeOnly, @Nullable XmlObject xml, @Nullable ExternalDocumentationObject externalDocs, @Nullable Object example, @Nullable Boolean deprecated) {
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
            List<@Nullable String> enums = new ArrayList<>();
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
    public @Nullable String title() {
        return title;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMultipleOf}
     */
    public @Nullable Double multipleOf() {
        return multipleOf;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMaximum}
     */
    public @Nullable Double maximum() {
        return maximum;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withExclusiveMaximum}
     */
    public @Nullable Boolean exclusiveMaximum() {
        return exclusiveMaximum;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMinimum}
     */
    public @Nullable Double minimum() {
        return minimum;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withExclusiveMinimum}
     */
    public @Nullable Boolean exclusiveMinimum() {
        return exclusiveMinimum;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMaxLength}
     */
    public @Nullable Integer maxLength() {
        return maxLength;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMinLength}
     */
    public @Nullable Integer minLength() {
        return minLength;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withPattern}
     */
    public @Nullable Pattern pattern() {
        return pattern;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMaxItems}
     */
    public @Nullable Integer maxItems() {
        return maxItems;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMinItems}
     */
    public @Nullable Integer minItems() {
        return minItems;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withUniqueItems}
     */
    public @Nullable Boolean uniqueItems() {
        return uniqueItems;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMaxProperties}
     */
    public @Nullable Integer maxProperties() {
        return maxProperties;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withMinProperties}
     */
    public @Nullable Integer minProperties() {
        return minProperties;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withRequired}
     */
    public @Nullable List<String> required() {
        return required;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withEnumValue}
     */
    public @Nullable List<Object> enumValue() {
        return enumValue;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withType}
     */
    public @Nullable String type() {
        return type;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withAllOf}
     */
    public @Nullable List<SchemaObject> allOf() {
        return allOf;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withOneOf}
     */
    public @Nullable List<SchemaObject> oneOf() {
        return oneOf;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withAnyOf}
     */
    public @Nullable List<SchemaObject> anyOf() {
        return anyOf;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withNot}
     */
    public @Nullable List<SchemaObject> not() {
        return not;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withItems}
     */
    public @Nullable SchemaObject items() {
        return items;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withProperties}
     */
    public @Nullable Map<String, SchemaObject> properties() {
        return properties;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withAdditionalProperties}
     */
    public @Nullable Object additionalProperties() {
        return additionalProperties;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withFormat}
     */
    public @Nullable String format() {
        return format;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withDefaultValue}
     */
    public @Nullable Object defaultValue() {
        return defaultValue;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withNullable}
     */
    public @Nullable Boolean nullable() {
        return nullable;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withDiscriminator}
     */
    public @Nullable DiscriminatorObject discriminator() {
        return discriminator;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withReadOnly}
     */
    public @Nullable Boolean readOnly() {
        return readOnly;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withWriteOnly}
     */
    public @Nullable Boolean writeOnly() {
        return writeOnly;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withXml}
     */
    public @Nullable XmlObject xml() {
        return xml;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withExternalDocs}
     */
    public @Nullable ExternalDocumentationObject externalDocs() {
        return externalDocs;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withExample}
     */
    public @Nullable Object example() {
        return example;
    }

    /**
     * @return the value described by {@link SchemaObjectBuilder#withDeprecated}
     */
    public @Nullable Boolean deprecated() {
        return deprecated;
    }
}
