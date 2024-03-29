package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;

import static io.muserver.openapi.Jsonizer.append;

/**
 * @see XmlObjectBuilder
 */
public class XmlObject implements JsonWriter {

    private final String name;
    private final URI namespace;
    private final String prefix;
    private final boolean attribute;
    private final boolean wrapped;

    XmlObject(String name, URI namespace, String prefix, boolean attribute, boolean wrapped) {
        this.name = name;
        this.namespace = namespace;
        this.prefix = prefix;
        this.attribute = attribute;
        this.wrapped = wrapped;
    }

    @Override
    public void writeJson(Writer writer) throws IOException {
        writer.write('{');
        boolean isFirst = true;
        isFirst = append(writer, "name", name, isFirst);
        isFirst = append(writer, "namespace", namespace, isFirst);
        isFirst = append(writer, "prefix", prefix, isFirst);
        isFirst = append(writer, "attribute", attribute, isFirst);
        isFirst = append(writer, "wrapped", wrapped, isFirst);
        writer.write('}');
    }

    /**
     * @return the value described by {@link XmlObjectBuilder#withName}
     */
    public String name() {
        return name;
    }

    /**
      @return the value described by {@link XmlObjectBuilder#withNamespace}
     */
    public URI namespace() {
        return namespace;
    }

    /**
      @return the value described by {@link XmlObjectBuilder#withPrefix}
     */
    public String prefix() {
        return prefix;
    }

    /**
      @return the value described by {@link XmlObjectBuilder#withAttribute}
     */
    public boolean attribute() {
        return attribute;
    }

    /**
      @return the value described by {@link XmlObjectBuilder#withWrapped}
     */
    public boolean wrapped() {
        return wrapped;
    }
}
