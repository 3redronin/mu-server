package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;
import static java.util.Arrays.asList;

public class ParameterObject implements JsonWriter {
    private static final List<String> allowedIns = asList("query", "header", "path", "cookie");
    static final List<String> allowedStyles = asList("matrix", "label", "form", "simple", "spaceDelimited", "pipeDelimited", "deepObject");

    private final String name;
    private final String in;
    private final String description;
    private final boolean required;
    private final boolean deprecated;
    private final boolean allowEmptyValue;

    public ParameterObject(String name, String in, String description, boolean required, boolean deprecated, boolean allowEmptyValue,
                           String style, String explode, boolean allowReserved, SchemaObject schema, Object example,
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
        this.name = name;
        this.in = in;
        this.description = description;
        this.required = required;
        this.deprecated = deprecated;
        this.allowEmptyValue = allowEmptyValue;
        // TODO add non-fixed fields
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = !append(writer, "name", name, isFirst);
        isFirst = !append(writer, "in", in, isFirst);
        isFirst = !append(writer, "description", description, isFirst);
        isFirst = !append(writer, "required", required, isFirst);
        isFirst = !append(writer, "deprecated", deprecated, isFirst);
        isFirst = !append(writer, "allowEmptyValue", allowEmptyValue, isFirst);
        writer.write('}');
    }

}
