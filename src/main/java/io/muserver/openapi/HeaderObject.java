package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.openapi.Jsonizer.append;
import static io.muserver.openapi.ParameterObject.allowedStyles;

/**
 * @see HeaderObjectBuilder
 */
public class HeaderObject implements JsonWriter {

    private final String description;
    private final boolean required;
    private final boolean deprecated;
    private final String style;
    private final String explode;
    private final SchemaObject schema;
    private final Object example;
    private final Map<String, ExampleObject> examples;
    private final Map<String, MediaTypeObject> content;

    HeaderObject(String description, boolean required, boolean deprecated,
                    String style, String explode, SchemaObject schema, Object example,
                    Map<String, ExampleObject> examples, Map<String, MediaTypeObject> content) {

        if (style != null && !allowedStyles.contains(style)) {
            throw new IllegalArgumentException("'style' must be one of " + allowedStyles + " but was " + style);
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

}
