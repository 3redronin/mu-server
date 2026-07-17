package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.openapi.Jsonizer.append;

/**
 * @see LinkObjectBuilder
 */
public class LinkObject implements JsonWriter {

    private final @Nullable String operationId;
    private final @Nullable Map<String, Object> parameters;
    private final @Nullable Object requestBody;
    private final @Nullable String description;
    private final @Nullable ServerObject server;

    LinkObject(@Nullable String operationId, @Nullable Map<String, Object> parameters, @Nullable Object requestBody, @Nullable String description, @Nullable ServerObject server) {
        this.operationId = operationId;
        this.parameters = parameters;
        this.requestBody = requestBody;
        this.description = description;
        this.server = server;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "operationId", operationId, isFirst);
        isFirst = append(writer, "parameters", parameters, isFirst);
        isFirst = append(writer, "requestBody", requestBody, isFirst);
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "server", server, isFirst);
        writer.write('}');
    }

    /**
     * @return the value described by {@link LinkObjectBuilder#withOperationId}
     */
    public @Nullable String operationId() {
        return operationId;
    }

    /**
      @return the value described by {@link LinkObjectBuilder#withParameters}
     */
    public @Nullable Map<String, Object> parameters() {
        return parameters;
    }

    /**
      @return the value described by {@link LinkObjectBuilder#withRequestBody}
     */
    public @Nullable Object requestBody() {
        return requestBody;
    }

    /**
      @return the value described by {@link LinkObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
      @return the value described by {@link LinkObjectBuilder#withServer}
     */
    public @Nullable ServerObject server() {
        return server;
    }
}
