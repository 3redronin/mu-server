package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see ServerVariableObjectBuilder
 */
public class ServerVariableObject implements JsonWriter {
    private final List<String> enumValues;
    private final String defaultValue;
    private final String description;

    ServerVariableObject(List<String> enumValues, String defaultValue, String description) {
        notNull("defaultValue", defaultValue);
        this.enumValues = enumValues;
        this.defaultValue = defaultValue;
        this.description = description;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write("{");
        boolean isFirst = true;
        isFirst = append(writer, "enum", enumValues, isFirst);
        isFirst = append(writer, "default", defaultValue, isFirst);
        isFirst = append(writer, "description", description, isFirst);
        writer.write("}");
    }

    /**
     * @return the value described by {@link ServerVariableObjectBuilder#withEnumValues}
     */
    public List<String> enumValues() {
        return enumValues;
    }

    /**
      @return the value described by {@link ServerVariableObjectBuilder#withDefaultValue}
     */
    public String defaultValue() {
        return defaultValue;
    }

    /**
      @return the value described by {@link ServerVariableObjectBuilder#withDescription}
     */
    public String description() {
        return description;
    }
}
