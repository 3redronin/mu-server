package io.muserver.openapi;

import org.jspecify.annotations.Nullable;

import io.muserver.Mutils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static io.muserver.openapi.OpenApiUtils.immutable;

/**
 * <p>A container for the expected responses of an operation. The container maps a HTTP response code to the expected response.</p>
 * <p>The documentation is not necessarily expected to cover all possible HTTP response codes because they may not be known
 * in advance. However, documentation is expected to cover a successful operation response and any known errors.</p>
 * <p>The <code>default</code> MAY be used as a default response object for all HTTP codes that are not covered
 * individually by the specification.</p>
 * <p>The <code>Responses Object</code> MUST contain at least one response code, and it SHOULD be the response for a
 * successful operation call.</p>
 */
public class ResponsesObjectBuilder {
    private @Nullable Map<String, Object> extensions;
    private @Nullable ReferenceOr<ResponseObject> defaultValue;
    private @Nullable Map<String, ReferenceOr<ResponseObject>> httpStatusCodes;

    /**
     *
     * @param defaultValue The documentation of responses other than the ones declared for specific HTTP response codes.
     *                     Use this field to cover undeclared responses.
     *
     * @return The current builder
     */
    public ResponsesObjectBuilder withDefaultValue(@Nullable ResponseObject defaultValue) {
        this.defaultValue = ReferenceValues.inline(defaultValue);
        return this;
    }

    /**
     *
     * @param httpStatusCodes The response codes, or null to clear them. To define a range of response codes,
     *                        this field MAY contain the uppercase wildcard character
     *                        <code>X</code>. For example, <code>2XX</code> represents all response codes between
     *                        <code>[200-299]</code>. The following range definitions are allowed: <code>1XX</code>,
     *                        <code>2XX</code>, <code>3XX</code>, <code>4XX</code>, and <code>5XX</code>. If a response
     *                        range is defined using an explicit code, the explicit code definition takes precedence over
     *                        the range definition for that code.
     *
     * @return The current builder
     */
    public ResponsesObjectBuilder withHttpStatusCodes(@Nullable Map<String, ResponseObject> httpStatusCodes) {
        this.httpStatusCodes = ReferenceValues.inline(httpStatusCodes);
        return this;
    }

    /**
     * @return A new object
     */
    public ResponsesObject build() {
        return new ResponsesObject(defaultValue, immutable(httpStatusCodes), extensions);
    }

    /**
     * Creates a builder for a {@link ResponsesObject}
     *
     * @return A new builder
     */
    public static ResponsesObjectBuilder responsesObject() {
        return new ResponsesObjectBuilder();
    }

    /**
     * Creates a new build by merging two exising responses
     *
     * @param primary A responses object to use. This is the dominant response who's values will
     *                 be preferred when values cannot be merged (such as {@link ResponseObject#description()}
     *
     * @param secondary The other responses object
     *
     * @return A builder that is the merged value of the two given ones
     */
    public static ResponsesObjectBuilder mergeResponses(@Nullable ResponsesObject primary, @Nullable ResponsesObject secondary) {
        if (primary == null) return secondary == null ? responsesObject() : secondary.toBuilder();
        if (secondary == null) return primary.toBuilder();
        Map<String, ReferenceOr<ResponseObject>> codes = new java.util.TreeMap<>(primary.httpStatusCodesOrReferences());
        secondary.httpStatusCodesOrReferences().forEach((key, value) -> codes.merge(key, value, ResponsesObjectBuilder::mergeReference));
        ReferenceOr<ResponseObject> defaultResponse = primary.defaultValueOrReferences();
        if (secondary.defaultValueOrReferences() != null) defaultResponse = defaultResponse == null
            ? secondary.defaultValueOrReferences() : mergeReference(defaultResponse, secondary.defaultValueOrReferences());
        return primary.toBuilder().withHttpStatusCodesOrReferences(codes).withDefaultValueOrReferences(defaultResponse);
    }

    private static ReferenceOr<ResponseObject> mergeReference(ReferenceOr<ResponseObject> a, ReferenceOr<ResponseObject> b) {
        return a.isReference() || b.isReference() ? a : ReferenceOr.inline(ResponseObjectBuilder.mergeResponses(a.value(), b.value()).build());
    }


    /**
     * @param value the extensions value
     * @return this builder */
    public ResponsesObjectBuilder withExtensions(@Nullable Map<String, Object> value) { this.extensions = value; return this; }
    /**
     * @param name an x- extension name
     * @param value its JSON value
     * @return this builder */
    public ResponsesObjectBuilder withExtension(String name, @Nullable Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        if (extensions != null) copy.putAll(extensions);
        Extensions.put(copy, name, value);
        extensions = copy;
        return this;
    }
    /**
     * @param value inline values and references for defaultValue
     * @return this builder */
    public ResponsesObjectBuilder withDefaultValueOrReferences(@Nullable ReferenceOr<ResponseObject> value) { this.defaultValue = value; return this; }
    /**
     * @param value inline values and references for httpStatusCodes
     * @return this builder */
    public ResponsesObjectBuilder withHttpStatusCodesOrReferences(@Nullable Map<String, ReferenceOr<ResponseObject>> value) { this.httpStatusCodes = value; return this; }
}
