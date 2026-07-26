package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;

/**
 * Represents an example value for an OpenAPI request or response.
 *
 * @see ExampleObjectBuilder
 */
public class ExampleObject implements JsonWriter {

    private final @Nullable String summary;
    private final @Nullable String description;
    private final @Nullable Object value;
    private final @Nullable URI externalValue;

    ExampleObject(@Nullable String summary, @Nullable String description, @Nullable Object value, @Nullable URI externalValue) {
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
     * Returns the short example summary.
     *
     * @return the value described by {@link ExampleObjectBuilder#withSummary}
     */
    public @Nullable String summary() {
        return summary;
    }

    /**
     * Returns the example description.
     *
     * @return the value described by {@link ExampleObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
     * Returns the embedded example value.
     *
     * @return the value described by {@link ExampleObjectBuilder#withValue}
     */
    public @Nullable Object value() {
        return value;
    }

    /**
     * Returns the URL of an externally stored example.
     *
     * @return the value described by {@link ExampleObjectBuilder#withExternalValue}
     */
    public @Nullable URI externalValue() {
        return externalValue;
    }
}
