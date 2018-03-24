package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

public class ResponsesObject implements JsonWriter {

    public final ResponseObject defaultValue;
    public final Map<Integer, ResponseObject> httpStatusCodes;

    public ResponsesObject(ResponseObject defaultValue, Map<Integer, ResponseObject> httpStatusCodes) {
        notNull("httpStatusCodes", httpStatusCodes);
        if (httpStatusCodes.isEmpty()) {
            throw new IllegalArgumentException("'httpStatusCodes' must contain at least one value");
        }
        this.defaultValue = defaultValue;
        this.httpStatusCodes = httpStatusCodes;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = !append(writer, "default", defaultValue, isFirst);
        for (Map.Entry<Integer, ResponseObject> entry : httpStatusCodes.entrySet()) {
            isFirst = !append(writer, entry.getKey().toString(), entry.getValue(), isFirst);
        }
        writer.write('}');
    }
}
