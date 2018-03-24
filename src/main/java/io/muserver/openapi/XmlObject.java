package io.muserver.openapi;

import java.io.IOException;
import java.io.Writer;

import static io.muserver.openapi.Jsonizer.append;

public class XmlObject implements JsonWriter {

    public final String name;
    public final String namespace;
    public final String prefix;
    public final boolean attribute;
    public final boolean wrapped;

    public XmlObject(String name, String namespace, String prefix, boolean attribute, boolean wrapped) {
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
        isFirst = !append(writer, "name", name, isFirst);
        isFirst = !append(writer, "namespace", namespace, isFirst);
        isFirst = !append(writer, "prefix", prefix, isFirst);
        isFirst = !append(writer, "attribute", attribute, isFirst);
        isFirst = !append(writer, "wrapped", wrapped, isFirst);
        writer.write('}');
    }
}
