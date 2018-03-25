package io.muserver.openapi;

import java.util.Map;

/**
 * A single encoding definition applied to a single schema property.
 */
public class EncodingObjectBuilder {
    private String contentType;
    private Map<String, HeaderObject> headers;
    private String style;
    private Boolean explode;
    private boolean allowReserved;

    /**
     * @param contentType The Content-Type for encoding a specific property. Default value depends on the property type:
     *                    for <code>string</code> with <code>format</code> being <code>binary</code> – <code>application/octet-stream</code>;
     *                    for other primitive types – <code>text/plain</code>; for <code>object</code> - <code>application/json</code>;
     *                    for <code>array</code> – the default is defined based on the inner type. The value can be a specific media
     *                    type (e.g. <code>application/json</code>), a wildcard media type (e.g. <code>image/*</code>), or a
     *                    comma-separated list of the two types.
     * @return The current builder
     */
    public EncodingObjectBuilder withContentType(String contentType) {
        this.contentType = contentType;
        return this;
    }

    /**
     * @param headers A map allowing additional information to be provided as headers, for example <code>Content-Disposition</code>.
     *                <code>Content-Type</code> is described separately and SHALL be ignored in this section. This property SHALL
     *                be ignored if the request body media type is not a <code>multipart</code>.
     * @return The current builder
     */
    public EncodingObjectBuilder withHeaders(Map<String, HeaderObject> headers) {
        this.headers = headers;
        return this;
    }

    /**
     * @param style Describes how a specific property value will be serialized depending on its type.
     *              See {@link ParameterObjectBuilder#withStyle(String)} for details on the <code>style</code> property.
     *              The behavior follows the same values as <code>query</code> parameters, including default values.
     *              This property SHALL be ignored if the request body media type is not <code>application/x-www-form-urlencoded</code>.
     * @return The current builder
     */
    public EncodingObjectBuilder withStyle(String style) {
        this.style = style;
        return this;
    }

    /**
     * @param explode When this is true, property values of type <code>array</code> or <code>object</code> generate separate
     *                parameters for each value of the array, or key-value-pair of the map.  For other types of properties this
     *                property has no effect. When <code>style</code> is <code>form</code>, the default value is <code>true</code>.
     *                For all other styles, the default value is <code>false</code>. This property SHALL be ignored if the request
     *                body media type is not <code>application/x-www-form-urlencoded</code>.
     * @return The current builder
     */
    public EncodingObjectBuilder withExplode(boolean explode) {
        this.explode = explode;
        return this;
    }

    /**
     * @param allowReserved Determines whether the parameter value SHOULD allow reserved characters, as defined by
     *                      <a href="https://tools.ietf.org/html/rfc3986#section-2.2">RFC3986</a> <code>:/?#[]@!$&amp;'()*+,;=</code>
     *                      to be included without percent-encoding. The default value is <code>false</code>. This property
     *                      SHALL be ignored if the request body media type is not <code>application/x-www-form-urlencoded</code>.
     * @return The current builder
     */
    public EncodingObjectBuilder withAllowReserved(boolean allowReserved) {
        this.allowReserved = allowReserved;
        return this;
    }

    public EncodingObject build() {
        boolean explodeVal = this.explode == null ? "form".equals(style) : this.explode;
        return new EncodingObject(contentType, headers, style, explodeVal, allowReserved);
    }

    /**
     * Creates a builder for an {@link EncodingObject}
     *
     * @return A new builder
     */
    public static EncodingObjectBuilder encodingObject() {
        return new EncodingObjectBuilder();
    }
}