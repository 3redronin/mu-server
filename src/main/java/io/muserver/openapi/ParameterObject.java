package io.muserver.openapi;

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
    private static final List<String> allowedIns = asList("query", "header", "path", "cookie");
    private static final List<String> allowedStyles = asList("matrix", "label", "form", "simple", "spaceDelimited", "pipeDelimited", "deepObject");

    /**
     * Use {@link #name()} instead
     */
    @Deprecated
    public final String name;
    /**
     * Use {@link #in()} instead
     */
    @Deprecated
    public final String in;
    /**
     * Use {@link #description()} instead
     */
    @Deprecated
    public final String description;
    /**
     * Use {@link #required()} instead
     */
    @Deprecated
    public final boolean required;
    /**
     * Use {@link #deprecated()} instead
     */
    @Deprecated
    public final boolean deprecated;
    /**
     * Use {@link #allowEmptyValue()} instead
     */
    @Deprecated
    public final boolean allowEmptyValue;
    /**
     * Use {@link #style()} instead
     */
    @Deprecated
    public final String style;
    /**
     * Use {@link #explode()} instead
     */
    @Deprecated
    public final boolean explode;
    /**
     * Use {@link #allowReserved()} instead
     */
    @Deprecated
    public final boolean allowReserved;
    /**
     * Use {@link #schema()} instead
     */
    @Deprecated
    public final SchemaObject schema;
    /**
     * Use {@link #example()} instead
     */
    @Deprecated
    public final Object example;
    /**
     * Use {@link #examples()} instead
     */
    @Deprecated
    public final Map<String, ExampleObject> examples;
    /**
     * Use {@link #content()} instead
     */
    @Deprecated
    public final Map<String, MediaTypeObject> content;

    ParameterObject(String name, String in, String description, boolean required, boolean deprecated, boolean allowEmptyValue,
                    String style, boolean explode, boolean allowReserved, SchemaObject schema, Object example,
                    Map<String, ExampleObject> examples, Map<String, MediaTypeObject> content) {
        notNull("name", name);
        notNull("in", in);
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

    public static List<String> allowedIns() {
        return allowedIns;
    }

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
    public String description() {
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
        return deprecated;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withAllowEmptyValue}
     */
    public boolean allowEmptyValue() {
        return allowEmptyValue;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withStyle}
     */
    public String style() {
        return style;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withExplode}
     */
    public boolean explode() {
        return explode;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withAllowReserved}
     */
    public boolean allowReserved() {
        return allowReserved;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withSchema}
     */
    public SchemaObject schema() {
        return schema;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withExample}
     */
    public Object example() {
        return example;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withExamples}
     */
    public Map<String, ExampleObject> examples() {
        return examples;
    }

    /**
      @return the value described by {@link ParameterObjectBuilder#withContent}
     */
    public Map<String, MediaTypeObject> content() {
        return content;
    }
}
