package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.openapi.Jsonizer.append;

/**
 * Describes a possible link from a response to another operation.
 *
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
     * Gets the linked operation identifier.
     *
     * @return the value described by {@link LinkObjectBuilder#withOperationId}
     */
    public @Nullable String operationId() {
        return operationId;
    }

    /**
     * Gets the linked operation parameters.
     *
     * @return the value described by {@link LinkObjectBuilder#withParameters}
     */
    public @Nullable Map<String, Object> parameters() {
        return parameters;
    }

    /**
     * Gets the linked operation request body override.
     *
     * @return the value described by {@link LinkObjectBuilder#withRequestBody}
     */
    public @Nullable Object requestBody() {
        return requestBody;
    }

    /**
     * Gets the link description.
     *
     * @return the value described by {@link LinkObjectBuilder#withDescription}
     */
    public @Nullable String description() {
        return description;
    }

    /**
     * Gets the server override for the linked operation.
     *
     * @return the value described by {@link LinkObjectBuilder#withServer}
     */
    public @Nullable ServerObject server() {
        return server;
    }
}
