package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.util.Map;

import static io.muserver.Mutils.notNull;
import static io.muserver.openapi.Jsonizer.append;

/**
 * @see ResponseObjectBuilder
 */
public class ResponseObject implements JsonWriter {

    /**
     * @deprecated use {@link #description()} instead
     */
    @Deprecated
    public final String description;
    /**
      @deprecated use {@link #headers()} instead
     */
    @Deprecated
    public final Map<String, HeaderObject> headers;
    /**
      @deprecated use {@link #content()} instead
     */
    @Deprecated
    public final Map<String, MediaTypeObject> content;
    /**
      @deprecated use {@link #links()} instead
     */
    @Deprecated
    public final Map<String, LinkObject> links;

    ResponseObject(String description, Map<String, HeaderObject> headers, Map<String, MediaTypeObject> content, Map<String, LinkObject> links) {
        notNull("description", description);
        this.description = description;
        this.headers = headers;
        this.content = content;
        this.links = links;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "description", description, isFirst);
        isFirst = append(writer, "headers", headers, isFirst);
        isFirst = append(writer, "content", content, isFirst);
        isFirst = append(writer, "links", links, isFirst);
        writer.write('}');
    }

    /**
     * @return the value described by {@link ResponseObjectBuilder#withDescription}
     */
    public String description() {
        return description;
    }

    /**
      @return the value described by {@link ResponseObjectBuilder#withHeaders}
     */
    public Map<String, HeaderObject> headers() {
        return headers;
    }

    /**
      @return the value described by {@link ResponseObjectBuilder#withContent}
     */
    public Map<String, MediaTypeObject> content() {
        return content;
    }

    /**
      @return the value described by {@link ResponseObjectBuilder#withLinks}
     */
    public Map<String, LinkObject> links() {
        return links;
    }
}
