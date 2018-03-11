package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;

interface JsonWriter {
    void writeJson(Writer writer) throws IOException;
}
