package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see ResponsesObjectBuilder
 */
public class ResponsesObject implements JsonWriter {

    private final ResponseObject defaultValue;
    private final Map<String, ResponseObject> httpStatusCodes;

    ResponsesObject(ResponseObject defaultValue, Map<String, ResponseObject> httpStatusCodes) {
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
        isFirst = append(writer, "default", defaultValue, isFirst);
        for (Map.Entry<String, ResponseObject> entry : httpStatusCodes.entrySet()) {
            isFirst = append(writer, entry.getKey(), entry.getValue(), isFirst);
        }
        writer.write('}');
    }

    /**
     * @return the value described by {@link ResponsesObjectBuilder#withDefaultValue}
     */
    public ResponseObject defaultValue() {
        return defaultValue;
    }

    /**
      @return the value described by {@link ResponsesObjectBuilder#withHttpStatusCodes}
     */
    public Map<String, ResponseObject> httpStatusCodes() {
        return httpStatusCodes;
    }
}
