package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see DiscriminatorObjectBuilder
 */
public class DiscriminatorObject implements JsonWriter {
    private final String propertyName;
    private final Map<String, String> mapping;

    DiscriminatorObject(String propertyName, Map<String, String> mapping) {
        notNull("propertyName", propertyName);
        this.propertyName = propertyName;
        this.mapping = mapping;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "propertyName", propertyName, isFirst);
        isFirst = append(writer, "mapping", mapping, isFirst);
        writer.write('}');
    }

    /**
     * @return The value described by {@link DiscriminatorObjectBuilder#withPropertyName}
     */
    public String propertyName() {
        return propertyName;
    }

    /**
      @return The value described by {@link DiscriminatorObjectBuilder#withMapping}
     */
    public Map<String, String> mapping() {
        return mapping;
    }
}
