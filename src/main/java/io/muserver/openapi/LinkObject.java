package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.openapi.Jsonizer.append;

/**
 * @see LinkObjectBuilder
 */
public class LinkObject implements JsonWriter {

    /**
     * @deprecated use {@link #operationId()} instead
     */
    @Deprecated
    public final String operationId;
    /**
      @deprecated use {@link #parameters()} instead
     */
    @Deprecated
    public final Map<String, Object> parameters;
    /**
      @deprecated use {@link #requestBody()} instead
     */
    @Deprecated
    public final Object requestBody;
    /**
      @deprecated use {@link #description()} instead
     */
    @Deprecated
    public final String description;
    /**
      @deprecated use {@link #server()} instead
     */
    @Deprecated
    public final ServerObject server;

    LinkObject(String operationId, Map<String, Object> parameters, Object requestBody, String description, ServerObject server) {
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
    public String operationId() {
        return operationId;
    }

    /**
      @return the value described by {@link LinkObjectBuilder#withParameters}
     */
    public Map<String, Object> parameters() {
        return parameters;
    }

    /**
      @return the value described by {@link LinkObjectBuilder#withRequestBody}
     */
    public Object requestBody() {
        return requestBody;
    }

    /**
      @return the value described by {@link LinkObjectBuilder#withDescription}
     */
    public String description() {
        return description;
    }

    /**
      @return the value described by {@link LinkObjectBuilder#withServer}
     */
    public ServerObject server() {
        return server;
    }
}
