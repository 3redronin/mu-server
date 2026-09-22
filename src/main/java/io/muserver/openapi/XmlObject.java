package io.muserver.openapi;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;

import static io.muserver.openapi.Jsonizer.append;

/**
 * @see XmlObjectBuilder
 */
public class XmlObject implements JsonWriter {
    private final @Nullable String nodeType;
    private final Map<String, Object> extensions;

    private final @Nullable String name;
    private final @Nullable URI namespace;
    private final @Nullable String prefix;
    private final @Nullable Boolean attribute;
    private final @Nullable Boolean wrapped;

    XmlObject(@Nullable String name, @Nullable URI namespace, @Nullable String prefix, @Nullable Boolean attribute, @Nullable Boolean wrapped, @Nullable String nodeType, @Nullable Map<String, Object> extensions) {
        this.nodeType = nodeType;
        this.extensions = Extensions.copy(extensions);
        if (nodeType != null) {
            if (!java.util.Arrays.asList("element", "attribute", "text", "cdata", "none").contains(nodeType)) throw new IllegalArgumentException("Invalid XML nodeType");
            if (attribute != null || wrapped != null) throw new IllegalArgumentException("nodeType cannot be combined with attribute or wrapped");
        }
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
        isFirst = Jsonizer.append(writer, "nodeType", nodeType, isFirst);
        isFirst = Extensions.write(writer, extensions, isFirst);
        writer.write('}');
    }

    /**
     * @return the value described by {@link XmlObjectBuilder#withName}
     */
    public @Nullable String name() {
        return name;
    }

    /**
      @return the value described by {@link XmlObjectBuilder#withNamespace}
     */
    public @Nullable URI namespace() {
        return namespace;
    }

    /**
      @return the value described by {@link XmlObjectBuilder#withPrefix}
     */
    public @Nullable String prefix() {
        return prefix;
    }

    /**
      @return the value described by {@link XmlObjectBuilder#withAttribute}
     */
    public boolean attribute() {
        return Boolean.TRUE.equals(attribute);
    }

    /**
      @return the value described by {@link XmlObjectBuilder#withWrapped}
     */
    public boolean wrapped() {
        return Boolean.TRUE.equals(wrapped);
    }
    /** @return the extensions value */
    public Map<String, Object> extensions() { return extensions; }
    /** @return a builder preserving all fields and extensions */
    public XmlObjectBuilder toBuilder() {
        return new XmlObjectBuilder()
            .withNodeType(nodeType).withExtensions(extensions).withName(name).withNamespace(namespace).withPrefix(prefix).withAttribute(attribute).withWrapped(wrapped);
    }
    /**
     * @return the XML node type, or null when omitted
     * @see XmlObjectBuilder#withNodeType
     */
    public @Nullable String nodeType() { return nodeType; }
}
