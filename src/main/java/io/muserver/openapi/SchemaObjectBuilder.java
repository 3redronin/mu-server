package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import io.muserver.UploadedFile;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.Temporal;
import java.util.*;
import java.util.regex.Pattern;

import static io.muserver.openapi.OpenApiUtils.immutable;
import static java.util.Arrays.asList;

/**
 * <p>The Schema Object allows the definition of input and output data types. These types can be objects, but also
 * primitives and arrays. This object is an extended subset of the <a href="http://json-schema.org/">JSON Schema
 * Specification Wright Draft 00</a>.</p>
 * <p>For more information about the properties, see <a href="https://tools.ietf.org/html/draft-wright-json-schema-00">JSON
 * Schema Core</a> and <a href="https://tools.ietf.org/html/draft-wright-json-schema-validation-00" >JSON Schema Validation</a>.
 * Unless stated otherwise, the property definitions follow the JSON Schema.</p>
 */
public class SchemaObjectBuilder {
    private @Nullable String title;
    private @Nullable Double multipleOf;
    private @Nullable Double maximum;
    private @Nullable Boolean exclusiveMaximum;
    private @Nullable Double minimum;
    private @Nullable Boolean exclusiveMinimum;
    private @Nullable Integer maxLength;
    private @Nullable Integer minLength;
    private @Nullable Pattern pattern;
    private @Nullable Integer maxItems;
    private @Nullable Integer minItems;
    private @Nullable Boolean uniqueItems;
    private @Nullable Integer maxProperties;
    private @Nullable Integer minProperties;
    private @Nullable List<String> required;
    private @Nullable List<Object> enumValue;
    private @Nullable String type;
    private @Nullable List<SchemaObject> allOf;
    private @Nullable List<SchemaObject> oneOf;
    private @Nullable List<SchemaObject> anyOf;
    private @Nullable List<SchemaObject> not;
    private @Nullable SchemaObject items;
    private @Nullable Map<String, SchemaObject> properties;
    private @Nullable Object additionalProperties;
    private @Nullable String description;
    private @Nullable String format;
    private @Nullable Object defaultValue;
    private @Nullable Boolean nullable;
    private @Nullable DiscriminatorObject discriminator;
    private @Nullable Boolean readOnly;
    private @Nullable Boolean writeOnly;
    private @Nullable XmlObject xml;
    private @Nullable ExternalDocumentationObject externalDocs;
    private @Nullable Object example;
    private @Nullable Boolean deprecated;

    /**
     * Creates an empty schema object builder.
     */
    public SchemaObjectBuilder() {
    }

    /**
     * Gets the configured schema title.
     *
     * @return the value set by {@link #withTitle}
     */
    public @Nullable String title() {
        return title;
    }

    /**
     * Gets the configured multiple-of constraint.
     *
     * @return the value set by {@link #withMultipleOf}
     */
    public @Nullable Double multipleOf() {
        return multipleOf;
    }

    /**
     * Gets the configured maximum numeric value.
     *
     * @return the value set by {@link #withMaximum}
     */
    public @Nullable Double maximum() {
        return maximum;
    }

    /**
     * Gets whether the configured maximum is exclusive.
     *
     * @return the value set by {@link #withExclusiveMaximum}
     */
    public @Nullable Boolean exclusiveMaximum() {
        return exclusiveMaximum;
    }

    /**
     * Gets the configured minimum numeric value.
     *
     * @return the value set by {@link #withMinimum}
     */
    public @Nullable Double minimum() {
        return minimum;
    }

    /**
     * Gets whether the configured minimum is exclusive.
     *
     * @return the value set by {@link #withExclusiveMinimum}
     */
    public @Nullable Boolean exclusiveMinimum() {
        return exclusiveMinimum;
    }

    /**
     * Gets the configured maximum string length.
     *
     * @return the value set by {@link #withMaxLength}
     */
    public @Nullable Integer maxLength() {
        return maxLength;
    }

    /**
     * Gets the configured minimum string length.
     *
     * @return the value set by {@link #withMinLength}
     */
    public @Nullable Integer minLength() {
        return minLength;
    }

    /**
     * Gets the configured string pattern.
     *
     * @return the value set by {@link #withPattern}
     */
    public @Nullable Pattern pattern() {
        return pattern;
    }

    /**
     * Gets the configured maximum array size.
     *
     * @return the value set by {@link #withMaxItems}
     */
    public @Nullable Integer maxItems() {
        return maxItems;
    }

    /**
     * Gets the configured minimum array size.
     *
     * @return the value set by {@link #withMinItems}
     */
    public @Nullable Integer minItems() {
        return minItems;
    }

    /**
     * Gets whether array items must be unique.
     *
     * @return the value set by {@link #withUniqueItems}
     */
    public @Nullable Boolean uniqueItems() {
        return uniqueItems;
    }

    /**
     * Gets the configured maximum property count.
     *
     * @return the value set by {@link #withMaxProperties}
     */
    public @Nullable Integer maxProperties() {
        return maxProperties;
    }

    /**
     * Gets the configured minimum property count.
     *
     * @return the value set by {@link #withMinProperties}
     */
    public @Nullable Integer minProperties() {
        return minProperties;
    }

    /**
     * Gets the configured required property names.
     *
     * @return the value set by {@link #withRequired}
     */
    public @Nullable List<String> required() {
        return required;
    }

    /**
     * Gets the configured enum values.
     *
     * @return the value set by {@link #withEnumValue}
     */
    public @Nullable List<Object> enumValue() {
        return enumValue;
    }

    /**
     * Gets the configured schema type.
     *
     * @return the value set by {@link #withType}
     */
    public @Nullable String type() {
        return type;
    }

    /**
     * Gets the configured all-of schema list.
     *
     * @return the value set by {@link #withAllOf}
     */
    public @Nullable List<SchemaObject> allOf() {
        return allOf;
    }

    /**
     * Gets the configured one-of schema list.
     *
     * @return the value set by {@link #withOneOf}
     */
    public @Nullable List<SchemaObject> oneOf() {
        return oneOf;
    }

    /**
     * Gets the configured any-of schema list.
     *
     * @return the value set by {@link #withAnyOf}
     */
    public @Nullable List<SchemaObject> anyOf() {
        return anyOf;
    }

    /**
     * Gets the configured not schema list.
     *
     * @return the value set by {@link #withNot}
     */
    public @Nullable List<SchemaObject> not() {
        return not;
    }

    /**
     * Gets the configured item schema.
     *
     * @return the value set by {@link #withItems}
     */
    public @Nullable SchemaObject items() {
        return items;
    }

    /**
     * Gets the configured object properties.
     *
     * @return the value set by {@link #withProperties}
     */
    public @Nullable Map<String, SchemaObject> properties() {
        return properties;
    }

    /**
     * Gets the configured additional-properties rule.
     *
     * @return the value set by {@link #withAdditionalProperties}
     */
    public @Nullable Object additionalProperties() {
        return additionalProperties;
    }

    /**
     * Gets the configured schema description.
     *
     * @return the value set by {@link #withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
     * Gets the configured schema format.
     *
     * @return the value set by {@link #withFormat}
     */
    public @Nullable String format() {
        return format;
    }

    /**
     * Gets the configured default value.
     *
     * @return the value set by {@link #withDefaultValue}
     */
    public @Nullable Object defaultValue() {
        return defaultValue;
    }

    /**
     * Gets whether the schema is nullable.
     *
     * @return the value set by {@link #withNullable}
     */
    public @Nullable Boolean nullable() {
        return nullable;
    }

    /**
     * Gets the configured discriminator.
     *
     * @return the value set by {@link #withDiscriminator}
     */
    public @Nullable DiscriminatorObject discriminator() {
        return discriminator;
    }

    /**
     * Gets whether the schema is read-only.
     *
     * @return the value set by {@link #withReadOnly}
     */
    public @Nullable Boolean readOnly() {
        return readOnly;
    }

    /**
     * Gets whether the schema is write-only.
     *
     * @return the value set by {@link #withWriteOnly}
     */
    public @Nullable Boolean writeOnly() {
        return writeOnly;
    }

    /**
     * Gets the configured XML metadata.
     *
     * @return the value set by {@link #withXml}
     */
    public @Nullable XmlObject xml() {
        return xml;
    }

    /**
     * Gets the configured external documentation.
     *
     * @return the value set by {@link #withExternalDocs}
     */
    public @Nullable ExternalDocumentationObject externalDocs() {
        return externalDocs;
    }

    /**
     * Gets the configured example value.
     *
     * @return the value set by {@link #withExample}
     */
    public @Nullable Object example() {
        return example;
    }

    /**
     * Gets whether the schema is deprecated.
     *
     * @return the value set by {@link #withDeprecated}
     */
    public @Nullable Boolean deprecated() {
        return deprecated;
    }

    /**
     * Sets the schema title.
     *
     * @param title the name of this object type
     * @return this builder
     */
    public SchemaObjectBuilder withTitle(@Nullable String title) {
        this.title = title;
        return this;
    }

    /**
     * Restricts numeric values to be a multiple of the given value
     * @param multipleOf the multiple
     * @return this builder
     */
    public SchemaObjectBuilder withMultipleOf(@Nullable Double multipleOf) {
        this.multipleOf = multipleOf;
        return this;
    }

    /**
     * Sets the maximum numeric value.
     *
     * @param maximum The maximum allowed value for numeric values
     * @return this builder
     * @see #withExclusiveMaximum(Boolean)
     */
    public SchemaObjectBuilder withMaximum(@Nullable Double maximum) {
        this.maximum = maximum;
        return this;
    }

    /**
     * Sets whether the maximum numeric value is exclusive.
     *
     * @param exclusiveMaximum <code>true</code> if the value specified with {@link #withMaximum(Double)} is exclusive;
     *                         otherwise the default <code>false</code> means it is an inclusive number.
     * @return this builder
     * @see #withMaximum(Double)
     */
    public SchemaObjectBuilder withExclusiveMaximum(@Nullable Boolean exclusiveMaximum) {
        this.exclusiveMaximum = exclusiveMaximum;
        return this;
    }

    /**
     * Sets the minimum numeric value.
     *
     * @param minimum The minimum allowed value for numeric values
     * @return this builder
     * @see #withExclusiveMinimum(Boolean)
     */
    public SchemaObjectBuilder withMinimum(@Nullable Double minimum) {
        this.minimum = minimum;
        return this;
    }

    /**
     * Sets whether the minimum numeric value is exclusive.
     *
     * @param exclusiveMinimum <code>true</code> if the value specified with {@link #withMinimum(Double)} is exclusive;
     *                         otherwise the default <code>false</code> means it is an inclusive number.
     * @return this builder
     * @see #withMinimum(Double)
     */
    public SchemaObjectBuilder withExclusiveMinimum(@Nullable Boolean exclusiveMinimum) {
        this.exclusiveMinimum = exclusiveMinimum;
        return this;
    }

    /**
     * Sets the maximum string length.
     *
     * @param maxLength the maximum allowed length of string values
     * @return this builder
     */
    public SchemaObjectBuilder withMaxLength(@Nullable Integer maxLength) {
        this.maxLength = maxLength;
        return this;
    }

    /**
     * Sets the minimum string length.
     *
     * @param minLength the minimum allowed length of string values
     * @return this builder
     */
    public SchemaObjectBuilder withMinLength(@Nullable Integer minLength) {
        this.minLength = minLength;
        return this;
    }

    /**
     * Sets the string pattern constraint.
     *
     * @param pattern a regular expression that string values must match against
     * @return this builder
     */
    public SchemaObjectBuilder withPattern(@Nullable Pattern pattern) {
        this.pattern = pattern;
        return this;
    }

    /**
     * Sets the maximum array size.
     *
     * @param maxItems the maximum number of items allowed in an array value
     * @return this builder
     */
    public SchemaObjectBuilder withMaxItems(@Nullable Integer maxItems) {
        this.maxItems = maxItems;
        return this;
    }

    /**
     * Sets the minimum array size.
     *
     * @param minItems the minimum number of items allowed in an array value
     * @return this builder
     */
    public SchemaObjectBuilder withMinItems(@Nullable Integer minItems) {
        this.minItems = minItems;
        return this;
    }

    /**
     * Sets whether array items must be unique.
     *
     * @param uniqueItems if true, then all items in an array value must be unique
     * @return this builder
     */
    public SchemaObjectBuilder withUniqueItems(@Nullable Boolean uniqueItems) {
        this.uniqueItems = uniqueItems;
        return this;
    }

    /**
     * Sets the maximum property count.
     *
     * @param maxProperties the maximum number of properties allowed for an &quot;object&quot; type.
     * @return this builder
     */
    public SchemaObjectBuilder withMaxProperties(@Nullable Integer maxProperties) {
        this.maxProperties = maxProperties;
        return this;
    }

    /**
     * Sets the minimum property count.
     *
     * @param minProperties the minimum number of properties allowed for an &quot;object&quot; type.
     * @return this builder
     */
    public SchemaObjectBuilder withMinProperties(@Nullable Integer minProperties) {
        this.minProperties = minProperties;
        return this;
    }

    /**
     * Sets the required property names.
     *
     * @param required the list of properties that are required to have a value for an &quot;object&quot; type.
     * @return this builder
     */
    public SchemaObjectBuilder withRequired(@Nullable List<String> required) {
        this.required = required;
        return this;
    }

    /**
     * Sets the allowed enum values.
     *
     * @param enumValue the allowed values for an &quot;enum&quot; type
     * @return this builder
     */
    public SchemaObjectBuilder withEnumValue(@Nullable List<Object> enumValue) {
        this.enumValue = enumValue;
        return this;
    }

    /**
     * Sets the schema type.
     *
     * @param type the type of this schema object. One of <code>string</code>, <code>number</code>, <code>integer</code>, <code>boolean</code>, <code>array</code> or <code>object</code>
     * @return this builder
     */
    public SchemaObjectBuilder withType(@Nullable String type) {
        this.type = type;
        return this;
    }

    /**
     * Sets the schemas that must all match.
     *
     * @param allOf the schemas that the value must match
     * @return this builder
     */
    public SchemaObjectBuilder withAllOf(@Nullable List<SchemaObject> allOf) {
        this.allOf = allOf;
        return this;
    }

    /**
     * Forces a value to be one of several different schemas
     * @param oneOf the schemas the validate against
     * @return this builder
     * @see #withAnyOf(List)
     */
    public SchemaObjectBuilder withOneOf(@Nullable List<SchemaObject> oneOf) {
        this.oneOf = oneOf;
        return this;
    }

    /**
     * Forces a value to be any of a number of different schemas
     * @param anyOf the schemas the validate against
     * @return this builder
     * @see #withOneOf(List)
     */
    public SchemaObjectBuilder withAnyOf(@Nullable List<SchemaObject> anyOf) {
        this.anyOf = anyOf;
        return this;
    }

    /**
     * Sets the schemas that the value must not match.
     *
     * @param not schemas the value must not validate against
     * @return this builder
     */
    public SchemaObjectBuilder withNot(@Nullable List<SchemaObject> not) {
        this.not = not;
        return this;
    }

    /**
     * Sets the schema for array items.
     *
     * @param items the schema that items in an array object must validate against
     * @return this builder
     */
    public SchemaObjectBuilder withItems(@Nullable SchemaObject items) {
        this.items = items;
        return this;
    }

    /**
     * Sets the object property schemas.
     *
     * @param properties the schema objects of each property for an <code>object</code> type
     * @return this builder
     */
    public SchemaObjectBuilder withProperties(@Nullable Map<String, SchemaObject> properties) {
        this.properties = properties;
        return this;
    }

    /**
     * Defines how properties not covered by {@link #withProperties(Map)} are handled when the
     * type is <code>object</code>
     * @param additionalProperties If <code>false</code> then extra properties are not allowed.
     *                             If it is a schema object then any extra properties must validate
     *                             against this schema.
     * @return this builder
     */
    public SchemaObjectBuilder withAdditionalProperties(@Nullable Object additionalProperties) {
        this.additionalProperties = additionalProperties;
        return this;
    }

    /**
     * Sets the schema description.
     *
     * @param description a description of this type
     * @return this builder
     * @see #withTitle(String)
     */
    public SchemaObjectBuilder withDescription(@Nullable String description) {
        this.description = description;
        return this;
    }

    /**
     * This is used to further specify the format of <code>string</code> types.
     * <table>
     *     <caption>Example type/format combos</caption>
     *     <thead>
     *         <tr>
     *             <th>Type</th>
     *             <th>Format</th>
     *             <th>Description</th>
     *         </tr>
     *     </thead>
     *     <tbody>
     *         <tr>
     *             <td>number</td>
     *             <td></td>
     *             <td>Any numbers.</td>
     *         </tr>
     *         <tr>
     *             <td>number</td>
     *             <td>float</td>
     *             <td>Floating-point numbers.</td>
     *         </tr>
     *         <tr>
     *             <td>number</td>
     *             <td>double</td>
     *             <td>Floating-point numbers with double precision.</td>
     *         </tr>
     *         <tr>
     *             <td>integer</td>
     *             <td></td>
     *             <td>Integer numbers.</td>
     *         </tr>
     *         <tr>
     *             <td>integer</td>
     *             <td>in32</td>
     *             <td>Signed 32-bit integers (commonly used integer type).</td>
     *         </tr>
     *         <tr>
     *             <td>integer</td>
     *             <td>int64</td>
     *             <td>Signed 64-bit integers (long type).</td>
     *         </tr>
     *         <tr>
     *             <td>string</td>
     *             <td>date</td>
     *             <td>full-date notation as defined by RFC 3339, section 5.6, for example, <code>2021-02-12</code></td>
     *         </tr>
     *         <tr>
     *             <td>string</td>
     *             <td>date-time</td>
     *             <td>the date-time notation as defined by RFC 3339, section 5.6, for example, <code>2021-02-12T15:33:28Z</code></td>
     *         </tr>
     *         <tr>
     *             <td>string</td>
     *             <td>password</td>
     *             <td>a hint to UIs to mask the input</td>
     *         </tr>
     *         <tr>
     *             <td>string</td>
     *             <td>byte</td>
     *             <td>base64-encoded characters, for example, <code>U3dhZ2dlciByb2Nrcw==</code></td>
     *         </tr>
     *         <tr>
     *             <td>string</td>
     *             <td>binary</td>
     *             <td>binary data, used to describe files (not text)</td>
     *         </tr>
     *         <tr>
     *             <td>string</td>
     *             <td>email</td>
     *             <td>email addresses</td>
     *         </tr>
     *         <tr>
     *             <td>string</td>
     *             <td>uuid</td>
     *             <td>UUIDs such as <code>93d35de9-0083-4765-8b60-822258e8ffad</code></td>
     *         </tr>
     *         <tr>
     *             <td>string</td>
     *             <td>uri</td>
     *             <td>URIs, for example <code>https://muserver.io/</code></td>
     *         </tr>
     *         <tr>
     *             <td>string</td>
     *             <td>hostname</td>
     *             <td>A server hostname</td>
     *         </tr>
     *         <tr>
     *             <td>string</td>
     *             <td>ipv4</td>
     *             <td>An IP4 address</td>
     *         </tr>
     *         <tr>
     *             <td>string</td>
     *             <td>ipv6</td>
     *             <td>An IP6 address</td>
     *         </tr>
     *     </tbody>
     * </table>
     * <p>Custom formats may be specified too.</p>
     * @param format the format of the type specified by {@link #withType(String)}
     * @return this builder
     */
    public SchemaObjectBuilder withFormat(@Nullable String format) {
        this.format = format;
        return this;
    }

    /**
     * Sets the default value.
     *
     * @param defaultValue The default value to use when none is specified
     * @return this builder
     */
    public SchemaObjectBuilder withDefaultValue(@Nullable Object defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    /**
     * Sets whether the schema is nullable.
     *
     * @param nullable A <code>true</code> value adds <code>&quot;null&quot;</code> to the allowed type specified by the
     *                 <code>type</code> keyword, only if <code>type</code> is explicitly defined within the same Schema
     *                 Object. Other Schema Object constraints retain their defined behavior, and therefore may disallow
     *                 the use of <code>null</code> as a value. A <code>false</code> value leaves the specified or default
     *                 <code>type</code> unmodified. The default value is <code>false</code>.
     * @return The current builder
     */
    public SchemaObjectBuilder withNullable(@Nullable Boolean nullable) {
        this.nullable = nullable;
        return this;
    }

    /**
     * Sets the discriminator metadata.
     *
     * @param discriminator Adds support for polymorphism. The discriminator is an object name that is used to differentiate between other schemas which may satisfy the payload description.
     * @return The current builder
     */
    public SchemaObjectBuilder withDiscriminator(@Nullable DiscriminatorObject discriminator) {
        this.discriminator = discriminator;
        return this;
    }

    /**
     * Sets whether the schema is read-only.
     *
     * @param readOnly Relevant only for Schema <code>"properties"</code> definitions. Declares the property as "read only".
     *                 This means that it MAY be sent as part of a response but SHOULD NOT be sent as part of the request.
     *                 If the property is marked as <code>readOnly</code> being <code>true</code> and is in the <code>required</code>
     *                 list, the <code>required</code> will take effect on the response only. A property MUST NOT be marked
     *                 as both <code>readOnly</code> and <code>writeOnly</code> being <code>true</code>. Default value is <code>false</code>.
     * @return The current builder
     */
    public SchemaObjectBuilder withReadOnly(@Nullable Boolean readOnly) {
        this.readOnly = readOnly;
        return this;
    }

    /**
     * Sets whether the schema is write-only.
     *
     * @param writeOnly Relevant only for Schema <code>"properties"</code> definitions. Declares the property as "write only".
     *                  Therefore, it MAY be sent as part of a request but SHOULD NOT be sent as part of the response. If
     *                  the property is marked as <code>writeOnly</code> being <code>true</code> and is in the
     *                  <code>required</code> list, the <code>required</code> will take effect on the request only. A property
     *                  MUST NOT be marked as both <code>readOnly</code> and <code>writeOnly</code> being <code>true</code>.
     *                  Default value is <code>false</code>.
     * @return The current builder
     */
    public SchemaObjectBuilder withWriteOnly(@Nullable Boolean writeOnly) {
        this.writeOnly = writeOnly;
        return this;
    }

    /**
     * Sets the XML metadata for the schema.
     *
     * @param xml This MAY be used only on properties schemas. It has no effect on root schemas. Adds additional metadata
     *            to describe the XML representation of this property.
     * @return The current builder
     */
    public SchemaObjectBuilder withXml(@Nullable XmlObject xml) {
        this.xml = xml;
        return this;
    }

    /**
     * Sets the external documentation for the schema.
     *
     * @param externalDocs Additional external documentation for this schema.
     * @return The current builder
     */
    public SchemaObjectBuilder withExternalDocs(@Nullable ExternalDocumentationObject externalDocs) {
        this.externalDocs = externalDocs;
        return this;
    }

    /**
     * Sets an example value for the schema.
     *
     * @param example A free-form property to include an example of an instance for this schema. To represent examples
     *                that cannot be naturally represented in JSON or YAML, a string value can be used to contain the
     *                example with escaping where necessary.
     * @return The current builder
     */
    public SchemaObjectBuilder withExample(@Nullable Object example) {
        this.example = example;
        return this;
    }

    /**
     * Sets whether the schema is deprecated.
     *
     * @param deprecated Specifies that a schema is deprecated and SHOULD be transitioned out of usage. Default value is <code>false</code>.
     * @return The current builder
     */
    public SchemaObjectBuilder withDeprecated(@Nullable Boolean deprecated) {
        this.deprecated = deprecated;
        return this;
    }

    /**
     * Builds a schema object from the configured values.
     *
     * @return A new object
     */
    public SchemaObject build() {
        return new SchemaObject(title, multipleOf, maximum, exclusiveMaximum, minimum, exclusiveMinimum, maxLength,
            minLength, pattern, maxItems, minItems, uniqueItems, maxProperties, minProperties, immutable(required),
            immutable(enumValue), type, immutable(allOf), immutable(oneOf), immutable(anyOf), immutable(not),
            items, immutable(properties), additionalProperties, description, format, defaultValue, nullable,
            discriminator, readOnly, writeOnly, xml, externalDocs, example, deprecated);
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
        return schemaObjectFrom(from, null, false);
    }

    /**
     * Creates a builder for a {@link SchemaObject} with the type and format based on the given class and generic type.
     * @param from Type type to build from, e.g. if the type is <code>List.class</code> then the <code>type</code> will
     *             be set as <code>array</code>.
     * @param parameterizedType The generic type of the class, e.g. a String if the type is <code>List&lt;String&gt;</code>
     * @param required True if it's a required value
     * @return A new builder
     */
    public static SchemaObjectBuilder schemaObjectFrom(Class<?> from, @Nullable Type parameterizedType, boolean required) {
        Objects.requireNonNull(from, "from");
        if (from.equals(void.class) || from.equals(Void.class)) {
            return schemaObject();
        }
        parameterizedType = getUpperBound(parameterizedType);
        String jsonType = jsonType(from);
        SchemaObjectBuilder schemaObjectBuilder = schemaObject()
            .withType(jsonType)
            .withFormat(jsonFormat(from))
            .withExample(example(from))
            .withNullable((!from.isPrimitive() && !required) ? true : null)
            .withItems(itemsFor(from, parameterizedType, "array".equals(jsonType)));
        if (from.equals(UUID.class)) {
            schemaObjectBuilder
                .withPattern(Pattern.compile("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]"));
        } else if (from.isEnum()) {
            Object[] enumConstants = from.getEnumConstants();
            schemaObjectBuilder.withEnumValue(asList(enumConstants));
        }
        return schemaObjectBuilder;
    }

    private static @Nullable Object example(Class<?> clazz) {
        if (clazz.equals(UUID.class)) {
            return UUID.randomUUID();
        } else if (Temporal.class.isAssignableFrom(clazz)) {
            try {
                return clazz.getDeclaredMethod("now").invoke(null);
            } catch (Exception ignored) { }
        }
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
                SchemaObjectBuilder schemaObjectBuilder = schemaObject().withType("object");
                if (parameterizedType instanceof ParameterizedType) {
                    Type[] actualTypeArguments = ((ParameterizedType) parameterizedType).getActualTypeArguments();
                    if (actualTypeArguments.length == 1) {
                        Type argType = getUpperBound(actualTypeArguments[0]);
                        if (argType instanceof Class<?>) {
                            Class<?> argClass = (Class<?>) argType;
                            schemaObjectBuilder = schemaObjectFrom(argClass, null, true);
                        }
                    }
                }
                return schemaObjectBuilder.build();
            } else {
                return null;
            }
        }
        return schemaObjectFrom(componentType).build();
    }

    private static String jsonType(Class<?> type) {
        if (CharSequence.class.isAssignableFrom(type) || type.equals(byte.class) || type.equals(Byte.class)
            || type.isAssignableFrom(Date.class) || Temporal.class.isAssignableFrom(type) || isBinaryClass(type)
            || type.isAssignableFrom(UUID.class) || type.isEnum()) {
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

    private static @Nullable String jsonFormat(Class<?> type) {
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
        } else if (type.equals(Date.class) || type.equals(Instant.class)) {
            return "date-time";
        } else if (type.equals(LocalDate.class)) {
            return "date";
        } else if (isBinaryClass(type)) {
            return "binary";
        } else if (type.equals(UUID.class)) {
            return "uuid";
        }
        return null;
    }

    private static boolean isBinaryClass(Class<?> type) {
        return UploadedFile.class.isAssignableFrom(type) || File.class.isAssignableFrom(type)
            || InputStream.class.isAssignableFrom(type) || (type.isArray() && type.getComponentType().equals(byte.class));
    }

    @Override
    public String toString() {
        return "SchemaObjectBuilder{" +
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

}
