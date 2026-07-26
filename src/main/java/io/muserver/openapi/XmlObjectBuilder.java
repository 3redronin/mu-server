package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * A metadata object that allows for more fine-tuned XML model definitions. When using arrays, XML element names are not
 * inferred (for singular/plural forms) and the name property SHOULD be used to add that information.
 */
public class XmlObjectBuilder {
    private @Nullable String name;
    private @Nullable URI namespace;
    private @Nullable String prefix;
    private boolean attribute = false;
    private boolean wrapped = false;

    /**
     * Creates an empty XML object builder.
     */
    public XmlObjectBuilder() {
    }

    /**
     * Sets the XML element or attribute name override.
     *
     * @param name Replaces the name of the element/attribute used for the described schema property. When defined within <code>items</code>, it will affect the name of the individual XML elements within the list. When defined alongside <code>type</code> being <code>array</code> (outside the <code>items</code>), it will affect the wrapping element and only if <code>wrapped</code> is <code>true</code>. If <code>wrapped</code> is <code>false</code>, it will be ignored.
     * @return The current builder
     */
    public XmlObjectBuilder withName(@Nullable String name) {
        this.name = name;
        return this;
    }

    /**
     * Sets the XML namespace URI.
     *
     * @param namespace The URI of the namespace definition. Value MUST be in the form of an absolute URI.
     * @return The current builder
     */
    public XmlObjectBuilder withNamespace(@Nullable URI namespace) {
        this.namespace = namespace;
        return this;
    }

    /**
     * Sets the XML namespace prefix.
     *
     * @param prefix The prefix to be used for the name.
     * @return The current builder
     */
    public XmlObjectBuilder withPrefix(@Nullable String prefix) {
        this.prefix = prefix;
        return this;
    }

    /**
     * Sets whether the schema maps to an XML attribute.
     *
     * @param attribute Declares whether the property definition translates to an attribute instead of an element. Default value is false.
     * @return The current builder
     */
    public XmlObjectBuilder withAttribute(boolean attribute) {
        this.attribute = attribute;
        return this;
    }

    /**
     * Sets whether array values are XML-wrapped.
     *
     * @param wrapped MAY be used only for an array definition. Signifies whether the array is wrapped (for example,
     *                <code>&lt;books&gt;&lt;book/&gt;&lt;book/&gt;&lt;/books&gt;</code>) or unwrapped
     *                (<code>&lt;book/&gt;&lt;book/&gt;</code>). Default value is <code>false</code>. The definition takes
     *                effect only when defined alongside <code>type</code> being <code>array</code> (outside the <code>items</code>).
     * @return The current builder
     */
    public XmlObjectBuilder withWrapped(boolean wrapped) {
        this.wrapped = wrapped;
        return this;
    }

    /**
     * Builds an XML object from the configured values.
     *
     * @return A new object
     */
    public XmlObject build() {
        return new XmlObject(name, namespace, prefix, attribute, wrapped);
    }

    /**
     * Creates a builder for a {@link XmlObject}
     * @return A new builder
     */
    public static XmlObjectBuilder xmlObject() {
        return new XmlObjectBuilder();
    }
}