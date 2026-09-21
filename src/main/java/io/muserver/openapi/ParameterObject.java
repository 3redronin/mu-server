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
 * @see ParameterObjectBuilder
 */
public class ParameterObject implements JsonWriter {
    private final Map<String, Object> extensions;
    private static final List<String> allowedIns = asList("query", "header", "path", "cookie", "querystring");
    private static final List<String> allowedStyles = asList("matrix", "label", "form", "simple", "spaceDelimited", "pipeDelimited", "deepObject", "cookie");

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
    private final @Nullable Map<String, ReferenceOr<ExampleObject>> examples;
    private final @Nullable Map<String, ReferenceOr<MediaTypeObject>> content;

    ParameterObject(@Nullable String name, @Nullable String in, @Nullable String description, Boolean required, @Nullable Boolean deprecated, @Nullable Boolean allowEmptyValue,
                    @Nullable String style, @Nullable Boolean explode, @Nullable Boolean allowReserved, @Nullable SchemaObject schema, @Nullable Object example,
                    @Nullable Map<String, ReferenceOr<ExampleObject>> examples, @Nullable Map<String, ReferenceOr<MediaTypeObject>> content, @Nullable Map<String, Object> extensions) {
        this.extensions = Extensions.copy(extensions);
        notNull("name", name);
        java.util.Objects.requireNonNull(name);
        notNull("in", in);
        java.util.Objects.requireNonNull(in);
        if (!allowedIns.contains(in)) {
            throw new IllegalArgumentException("'in' must be one of " + allowedIns + " but was " + in);
        }
        if (style != null && !validStyle(in, style)) {
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
        if ((schema == null) == (content == null)) {
            throw new IllegalArgumentException("Either a schema or a content value must be specified");
        }
        if ("querystring".equals(in) && content == null) throw new IllegalArgumentException("querystring requires content");
        if (allowEmptyValue != null && !"query".equals(in)) throw new IllegalArgumentException("allowEmptyValue applies only to query");
        if (allowReserved != null && !"query".equals(in) && !"path".equals(in) && !("cookie".equals(in) && (style == null || "form".equals(style)))) throw new IllegalArgumentException("allowReserved applies only to query or path");
        if (content != null && allowReserved != null) throw new IllegalArgumentException("allowReserved requires schema");
        this.name = name;
        this.in = in;
        this.description = description;
        this.required = required;
        this.deprecated = deprecated;
        this.allowEmptyValue = allowEmptyValue;
        this.style = style;
        this.explode = explode;
        this.allowReserved = allowReserved;
        if (content != null && (style != null || explode != null)) {
            throw new IllegalArgumentException("Style, explode and examples belong to schema-based parameters");
        }
        this.schema = schema;
        this.example = example == null ? null : JsonValues.freeze(example);
        this.examples = examples;
        this.content = content;
    }

    /**
     * @return The values allowed to be passed to {@link ParameterObjectBuilder#withIn(String)}
     */
    public static List<String> allowedIns() {
        return allowedIns;
    }

    /**
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
        isFirst = append(writer, "deprecated", OpenApiDefaults.parameter("deprecated", deprecated, in, style), isFirst);
        isFirst = append(writer, "allowEmptyValue", OpenApiDefaults.parameter("allowEmptyValue", allowEmptyValue, in, style), isFirst);
        isFirst = append(writer, "style", OpenApiDefaults.parameter("style", style, in, style), isFirst);
        isFirst = append(writer, "explode", OpenApiDefaults.parameter("explode", explode, in, style), isFirst);
        isFirst = append(writer, "allowReserved", OpenApiDefaults.parameter("allowReserved", allowReserved, in, style), isFirst);
        isFirst = append(writer, "schema", schema, isFirst);
        isFirst = append(writer, "example", example, isFirst);
        isFirst = append(writer, "examples", examples, isFirst);
        isFirst = append(writer, "content", content, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
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
     * @return the value described by {@link ParameterObjectBuilder#withName}
     */
    public String name() {
        return name;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withIn}
     */
    public String in() {
        return in;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withRequired}
     */
    public boolean required() {
        return required;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withDeprecated}
     */
    public boolean deprecated() {
        return actualValue(deprecated, false);
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withAllowEmptyValue}
     */
    public boolean allowEmptyValue() {
        return actualValue(allowEmptyValue, false);
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withStyle}
     */
    public @Nullable String style() {
        return style;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withExplode}
     */
    public boolean explode() {
        return actualValue(explode, "form".equals(style == null ? defaultStyle(in) : style) || "cookie".equals(style));
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withAllowReserved}
     */
    public boolean allowReserved() {
        return actualValue(allowReserved, false);
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withSchema}
     */
    public @Nullable SchemaObject schema() {
        return schema;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withExample}
     */
    public @Nullable Object example() {
        return example;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withExamples}
     */
    public @Nullable Map<String, ExampleObject> examples() {
        return ReferenceValues.values(examples);
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withContent}
     */
    public @Nullable Map<String, MediaTypeObject> content() {
        return ReferenceValues.values(content);
    }

    static boolean actualValue(@Nullable Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return inline values and references for examples */
    public @Nullable Map<String, ReferenceOr<ExampleObject>> examplesOrReferences() { return examples; }
    /** @return a builder preserving all fields and extensions */
    public ParameterObjectBuilder toBuilder() {
        return new ParameterObjectBuilder()
            .withExtensions(extensions).withName(name).withIn(in).withDescription(description).withRequired(required).withDeprecated(deprecated).withAllowEmptyValue(allowEmptyValue).withStyle(style).withExplode(explode).withAllowReserved(allowReserved).withSchema(schema).withExample(example).withExamplesOrReferences(examples).withContentOrReferences(content);
    }
    static String defaultStyle(String in) { return "query".equals(in) || "cookie".equals(in) ? "form" : "simple"; }
    static boolean validStyle(String in, String style) {
        switch (in) {
            case "path": return asList("matrix", "label", "simple").contains(style);
            case "query": return asList("form", "spaceDelimited", "pipeDelimited", "deepObject").contains(style);
            case "cookie": return "form".equals(style) || "cookie".equals(style);
            case "header": return "simple".equals(style);
            default: return false;
        }
    }
    /** @return inline media types and references */
    public @Nullable Map<String, ReferenceOr<MediaTypeObject>> contentOrReferences() { return content; }
    static void validateLocations(@Nullable List<ReferenceOr<ParameterObject>> parameters) {
        int querystrings = 0;
        boolean query = false;
        if (parameters != null) for (ReferenceOr<ParameterObject> parameter : parameters) {
            if (parameter.isReference()) continue;
            if ("querystring".equals(parameter.value().in())) querystrings++;
            if ("query".equals(parameter.value().in())) query = true;
        }
        if (querystrings > 1 || (querystrings > 0 && query)) throw new IllegalArgumentException("A single querystring parameter cannot coexist with query parameters");
    }
}
