package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;
import static java.util.Arrays.asList;

/**
 * Describes a parameter accepted by an OpenAPI operation.
 *
 * @see ParameterObjectBuilder
 */
public class ParameterObject implements JsonWriter {
    private static final List<String> allowedIns = asList("query", "header", "path", "cookie");
    private static final List<String> allowedStyles = asList("matrix", "label", "form", "simple", "spaceDelimited", "pipeDelimited", "deepObject");

    private final String name;
    private final String in;
    private final @Nullable String description;
    private final boolean required;
    private final @Nullable Boolean deprecated;
    private final @Nullable Boolean allowEmptyValue;
    private final @Nullable String style;
    private final @Nullable Boolean explode;
    private final @Nullable Boolean allowReserved;
    private final @Nullable SchemaObject schema;
    private final @Nullable Object example;
    private final @Nullable Map<String, ExampleObject> examples;
    private final @Nullable Map<String, MediaTypeObject> content;

    ParameterObject(@Nullable String name, @Nullable String in, @Nullable String description, Boolean required, @Nullable Boolean deprecated, @Nullable Boolean allowEmptyValue,
                    @Nullable String style, @Nullable Boolean explode, @Nullable Boolean allowReserved, @Nullable SchemaObject schema, @Nullable Object example,
                    @Nullable Map<String, ExampleObject> examples, @Nullable Map<String, MediaTypeObject> content) {
        notNull("name", name);
        java.util.Objects.requireNonNull(name);
        notNull("in", in);
        java.util.Objects.requireNonNull(in);
        if (!allowedIns.contains(in)) {
            throw new IllegalArgumentException("'in' must be one of " + allowedIns + " but was " + in);
        }
        if (style != null && !allowedStyles.contains(style)) {
            throw new IllegalArgumentException("'style' must be one of " + allowedStyles + " but was " + style);
        }
        if (content != null && content.size() != 1) {
            throw new IllegalArgumentException("'content', when specified, must have a single value only, but was " + content);
        }
        if (example != null && examples != null) {
            throw new IllegalArgumentException("Only one of 'example' and 'examples' can be supplied");
        }
        if ("path".equals(in) && !required) {
            throw new IllegalArgumentException("'required' must be true for " + name + " because in is '" + in + "'");
        }
        if (schema == null && content == null) {
            throw new IllegalArgumentException("Either a schema or a content value must be specified");
        }
        this.name = name;
        this.in = in;
        this.description = description;
        this.required = required;
        this.deprecated = deprecated;
        this.allowEmptyValue = allowEmptyValue;
        this.style = style;
        this.explode = explode;
        this.allowReserved = allowReserved;
        this.schema = schema;
        this.example = example;
        this.examples = examples;
        this.content = content;
    }

    /**
     * Gets the allowed values for the <code>in</code> field.
     *
     * @return The values allowed to be passed to {@link ParameterObjectBuilder#withIn(String)}
     */
    public static List<String> allowedIns() {
        return allowedIns;
    }

    /**
     * Gets the allowed values for the <code>style</code> field.
     *
     * @return The values allowed to be passed to {@link ParameterObjectBuilder#withStyle(String)}
     */
    public static List<String> allowedStyles() {
        return allowedStyles;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "name", name, isFirst);
        isFirst = append(writer, "in", in, isFirst);
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "required", required, isFirst);
        isFirst = append(writer, "deprecated", deprecated, isFirst);
        isFirst = append(writer, "allowEmptyValue", allowEmptyValue, isFirst);
        isFirst = append(writer, "style", style, isFirst);
        isFirst = append(writer, "explode", explode, isFirst);
        isFirst = append(writer, "allowReserved", allowReserved, isFirst);
        isFirst = append(writer, "schema", schema, isFirst);
        isFirst = append(writer, "example", example, isFirst);
        isFirst = append(writer, "examples", examples, isFirst);
        isFirst = append(writer, "content", content, isFirst);
        writer.write('}');
    }

    @Override
    public String toString() {
        Writer writer = new StringWriter();
        try {
            writeJson(writer);
        } catch (IOException e) {
            return "Error from " + getClass() + " - " + e;
        }
        return writer.toString();
    }

    /**
     * Gets the parameter name.
     *
     * @return the value described by {@link ParameterObjectBuilder#withName}
     */
    public String name() {
        return name;
    }

    /**
     * Gets where the parameter is located in the request.
     *
     * @return the value described by {@link ParameterObjectBuilder#withIn}
     */
    public String in() {
        return in;
    }

    /**
     * Gets the parameter description.
     *
     * @return the value described by {@link ParameterObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
     * Indicates whether the parameter is required.
     *
     * @return the value described by {@link ParameterObjectBuilder#withRequired}
     */
    public boolean required() {
        return required;
    }

    /**
     * Indicates whether the parameter is deprecated.
     *
     * @return the value described by {@link ParameterObjectBuilder#withDeprecated}
     */
    public boolean deprecated() {
        return actualValue(deprecated, false);
    }

    /**
     * Indicates whether the parameter accepts empty values.
     *
     * @return the value described by {@link ParameterObjectBuilder#withAllowEmptyValue}
     */
    public boolean allowEmptyValue() {
        return actualValue(allowEmptyValue, false);
    }

    /**
     * Gets the parameter serialization style.
     *
     * @return the value described by {@link ParameterObjectBuilder#withStyle}
     */
    public @Nullable String style() {
        return style;
    }

    /**
     * Indicates whether structured parameter values are exploded.
     *
     * @return the value described by {@link ParameterObjectBuilder#withExplode}
     */
    public boolean explode() {
        return actualValue(explode, style == null || "form".equals(style));
    }

    /**
     * Indicates whether reserved characters may appear unescaped.
     *
     * @return the value described by {@link ParameterObjectBuilder#withAllowReserved}
     */
    public boolean allowReserved() {
        return actualValue(allowReserved, false);
    }

    /**
     * Gets the schema used for the parameter.
     *
     * @return the value described by {@link ParameterObjectBuilder#withSchema}
     */
    public @Nullable SchemaObject schema() {
        return schema;
    }

    /**
     * Gets the example value for the parameter.
     *
     * @return the value described by {@link ParameterObjectBuilder#withExample}
     */
    public @Nullable Object example() {
        return example;
    }

    /**
     * Gets the named examples for the parameter.
     *
     * @return the value described by {@link ParameterObjectBuilder#withExamples}
     */
    public @Nullable Map<String, ExampleObject> examples() {
        return examples;
    }

    /**
     * Gets the content representations for the parameter.
     *
     * @return the value described by {@link ParameterObjectBuilder#withContent}
     */
    public @Nullable Map<String, MediaTypeObject> content() {
        return content;
    }

    static boolean actualValue(@Nullable Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }
}
