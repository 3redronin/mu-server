package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;
import static io.muserver.openapi.ParameterObject.actualValue;

/**
 * @see RequestBodyObjectBuilder
 */
public class RequestBodyObject implements JsonWriter {

    private final @Nullable String description;
    private final Map<String, MediaTypeObject> content;
    private final @Nullable Boolean required;

    RequestBodyObject(@Nullable String description, Map<String, MediaTypeObject> content, @Nullable Boolean required) {
        notNull("content", content);
        this.description = description;
        this.content = content;
        this.required = required;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "content", content, isFirst);
        isFirst = append(writer, "required", required, isFirst);
        writer.write('}');
    }

    /**
     * @return the value described by {@link RequestBodyObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
      @return the value described by {@link RequestBodyObjectBuilder#withContent}
     */
    public Map<String, MediaTypeObject> content() {
        return content;
    }

    /**
      @return the value described by {@link RequestBodyObjectBuilder#withRequired}
     */
    public boolean required() {
        return actualValue(required, false);
    }
}
