package io.muserver.openapi;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;

/**
 * An object that can serialize itself to JSON
 */
interface JsonWriter {
    /**
     * Writes this object as a JSON Object
     * @param writer The writer to write to
     * @throws IOException Thrown if the writer throws this while writing
     */
    void writeJson(Writer writer) throws IOException;

    /**
     * Writes this object as YAML.
     *
     * @param writer The writer to write to
     * @throws IOException Thrown if the writer throws this while writing
     */
    default void writeYaml(Writer writer) throws IOException {
        try (StringWriter jsonWriter = new StringWriter()) {
            writeJson(jsonWriter);
            Yamlizer.writeJsonAsYaml(writer, jsonWriter.toString());
        }
    }
}
