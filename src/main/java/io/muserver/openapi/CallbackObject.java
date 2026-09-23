package io.muserver.openapi;

import org.jspecify.annotations.Nullable;
import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;

/**
 * Describes callback path items keyed by runtime expressions.
 *
 * @see CallbackObjectBuilder
 */
public class CallbackObject implements JsonWriter {
    private final Map<String, Object> extensions;

    private final Map<String, PathItemObject> callbacks;

    CallbackObject(@Nullable Map<String, PathItemObject> callbacks, @Nullable Map<String, Object> extensions) {
        this.extensions = Extensions.copy(extensions);
        notNull("callbacks", callbacks);
        this.callbacks = java.util.Objects.requireNonNull(callbacks);
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.append('{');

        boolean isFirst = true;
        for (Map.Entry<String, PathItemObject> entry : callbacks.entrySet()) {
            isFirst = Jsonizer.append(writer, entry.getKey(), entry.getValue(), isFirst);
        }

        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.append('}');

    }

    /**
     * Returns the callback path items keyed by runtime expression.
     *
     * @return the value described by {@link CallbackObjectBuilder#withCallbacks}
     */
    public Map<String, PathItemObject> callbacks() {
        return callbacks;
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public CallbackObjectBuilder toBuilder() {
        return new CallbackObjectBuilder()
            .withExtensions(extensions).withCallbacks(callbacks);
    }
}
