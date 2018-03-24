package io.muserver.openapi;

import io.muserver.rest.NotImplementedException;

import java.io.IOException;
import java.io.Writer;

public class HeaderObject implements JsonWriter {

    @Override
    public void writeJson(Writer writer) throws IOException {
        throw new NotImplementedException("Not yet");
    }
}
