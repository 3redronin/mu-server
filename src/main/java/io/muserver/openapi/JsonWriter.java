package io.muserver.openapi;

import java.io.IOException;
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
}
