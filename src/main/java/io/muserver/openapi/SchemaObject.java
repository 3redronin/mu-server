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

    public final String title;
    public final Double multipleOf;
    public final Double maximum;
    public final Boolean exclusiveMaximum;
    public final Double minimum;
    public final Boolean exclusiveMinimum;
    public final Integer maxLength;
    public final Integer minLength;
    public final Pattern pattern;
    public final Integer maxItems;
    public final Integer minItems;
    public final Boolean uniqueItems;
    public final Integer maxProperties;
    public final Integer minProperties;
    public final List<String> required;
    public final List<Object> enumValue;
    public final String type;
    public final List<SchemaObject> allOf;
    public final List<SchemaObject> oneOf;
    public final List<SchemaObject> anyOf;
    public final List<SchemaObject> not;
    public final SchemaObject items;
    public final Map<String, SchemaObject> properties;
    public final Object additionalProperties;
    public final String description;
    public final String format;
    public final Object defaultValue;
    public final Boolean nullable;
    public final DiscriminatorObject discriminator;
    public final Boolean readOnly;
    public final Boolean writeOnly;
    public final XmlObject xml;
    public final ExternalDocumentationObject externalDocs;
    public final Object example;
    public final Boolean deprecated;

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
            if (nullable) {
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

    public boolean isDeprecated() {
        return deprecated != null && deprecated;
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

}
