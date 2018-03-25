package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;

/**
 * @see CallbackObjectBuilder
 */
public class CallbackObject implements JsonWriter {

    private final Map<String, PathItemObject> callbacks;

    CallbackObject(Map<String, PathItemObject> callbacks) {
        notNull("callbacks", callbacks);
        this.callbacks = callbacks;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.append('{');

        boolean isFirst = true;
        for (Map.Entry<String, PathItemObject> entry : callbacks.entrySet()) {
            isFirst = Jsonizer.append(writer, entry.getKey(), entry.getValue(), isFirst);
        }

        writer.append('}');

    }
}
