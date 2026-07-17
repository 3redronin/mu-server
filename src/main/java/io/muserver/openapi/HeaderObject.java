package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.openapi.Jsonizer.append;
import static io.muserver.openapi.ParameterObject.actualValue;
import static io.muserver.openapi.ParameterObject.allowedStyles;

/**
 * @see HeaderObjectBuilder
 */
public class HeaderObject implements JsonWriter {

    private final @Nullable String description;
    private final @Nullable Boolean required;
    private final @Nullable Boolean deprecated;
    private final @Nullable String style;
    private final @Nullable Boolean explode;
    private final @Nullable SchemaObject schema;
    private final @Nullable Object example;
    private final @Nullable Map<String, ExampleObject> examples;
    private final @Nullable Map<String, MediaTypeObject> content;

    HeaderObject(@Nullable String description, @Nullable Boolean required, @Nullable Boolean deprecated,
                    @Nullable String style, @Nullable Boolean explode, @Nullable SchemaObject schema, @Nullable Object example,
                    @Nullable Map<String, ExampleObject> examples, @Nullable Map<String, MediaTypeObject> content) {

        if (style != null && !allowedStyles().contains(style)) {
            throw new IllegalArgumentException("'style' must be one of " + allowedStyles() + " but was " + style);
        }
        if (content != null && content.size() != 1) {
            throw new IllegalArgumentException("'content', when specified, must have a single value only, but was " + content);
        }
        if (example != null && examples != null) {
            throw new IllegalArgumentException("Only one of 'example' and 'examples' can be supplied");
        }
        this.description = description;
        this.required = required;
        this.deprecated = deprecated;
        this.style = style;
        this.explode = explode;
        this.schema = schema;
        this.example = example;
        this.examples = examples;
        this.content = content;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "required", required, isFirst);
        isFirst = append(writer, "deprecated", deprecated, isFirst);
        isFirst = append(writer, "style", style, isFirst);
        isFirst = append(writer, "explode", explode, isFirst);
        isFirst = append(writer, "schema", schema, isFirst);
        isFirst = append(writer, "example", example, isFirst);
        isFirst = append(writer, "examples", examples, isFirst);
        isFirst = append(writer, "content", content, isFirst);
        writer.write('}');
    }

    /**
     * @return the value described by {@link HeaderObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
     * @return the value described by {@link HeaderObjectBuilder#withRequired}
     */
    public boolean required() {
        return actualValue(required, false);
    }

    /**
     * @return the value described by {@link HeaderObjectBuilder#withDeprecated}
     */
    public boolean deprecated() {
        return actualValue(deprecated, false);
    }

    /**
     * @return the value described by {@link HeaderObjectBuilder#withStyle}
     */
    public @Nullable String style() {
        return style;
    }

    /**
     * @return the value described by {@link HeaderObjectBuilder#withExplode}
     */
    public boolean explode() {
        return actualValue(explode, style == null || "form".equals(style));
    }

    /**
     * @return the value described by {@link HeaderObjectBuilder#withSchema}
     */
    public @Nullable SchemaObject schema() {
        return schema;
    }

    /**
     * @return the value described by {@link HeaderObjectBuilder#withExample}
     */
    public @Nullable Object example() {
        return example;
    }

    /**
     * @return the value described by {@link HeaderObjectBuilder#withExamples}
     */
    public @Nullable Map<String, ExampleObject> examples() {
        return examples;
    }

    /**
     * @return the value described by {@link HeaderObjectBuilder#withContent}
     */
    public @Nullable Map<String, MediaTypeObject> content() {
        return content;
    }
}
