package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;

/**
 * @see ExampleObjectBuilder
 */
public class ExampleObject implements JsonWriter {

    private final String summary;
    private final String description;
    private final Object value;
    private final URI externalValue;

    ExampleObject(String summary, String description, Object value, URI externalValue) {
        if (value != null && externalValue != null) {
            throw new IllegalArgumentException("Only one of 'value' or 'externalValue' can have a value");
        }
        this.summary = summary;
        this.description = description;
        this.value = value;
        this.externalValue = externalValue;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.append('{');
        boolean isFirst = true;
        isFirst = Jsonizer.append(writer, "summary", summary, isFirst);
        isFirst = Jsonizer.append(writer, "description", description, isFirst);
        isFirst = Jsonizer.append(writer, "value", value, isFirst);
        isFirst = Jsonizer.append(writer, "externalValue", externalValue, isFirst);
        writer.append('}');
    }

    /**
     * @return the value described by {@link ExampleObjectBuilder#withSummary}
     */
    public String summary() {
        return summary;
    }

    /**
     * @return the value described by {@link ExampleObjectBuilder#withDescription}
     */
    public String description() {
        return description;
    }

    /**
     * @return the value described by {@link ExampleObjectBuilder#withValue}
     */
    public Object value() {
        return value;
    }

    /**
     * @return the value described by {@link ExampleObjectBuilder#withExternalValue}
     */
    public URI externalValue() {
        return externalValue;
    }
}
