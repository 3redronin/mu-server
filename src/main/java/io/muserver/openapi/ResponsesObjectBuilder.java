package io.muserver.openapi;

import io.muserver.Mutils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    private ResponseObject defaultValue;
    private Map<String, ResponseObject> httpStatusCodes;

    /**
     * @param defaultValue The documentation of responses other than the ones declared for specific HTTP response codes.
     *                     Use this field to cover undeclared responses.
     * @return The current builder
     */
    public ResponsesObjectBuilder withDefaultValue(ResponseObject defaultValue) {
        this.defaultValue = defaultValue;
        return this;
    }

    /**
     * @param httpStatusCodes To define a range of response codes, this field MAY contain the uppercase wildcard character
     *                        <code>X</code>. For example, <code>2XX</code> represents all response codes between
     *                        <code>[200-299]</code>. The following range definitions are allowed: <code>1XX</code>,
     *                        <code>2XX</code>, <code>3XX</code>, <code>4XX</code>, and <code>5XX</code>. If a response
     *                        range is defined using an explicit code, the explicit code definition takes precedence over
     *                        the range definition for that code.
     * @return The current builder
     */
    public ResponsesObjectBuilder withHttpStatusCodes(Map<String, ResponseObject> httpStatusCodes) {
        this.httpStatusCodes = httpStatusCodes;
        return this;
    }

    /**
     * @return A new object
     */
    public ResponsesObject build() {
        return new ResponsesObject(defaultValue, httpStatusCodes);
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
     * @param primary A responses object to use. This is the dominant response who's values will
     *                 be preferred when values cannot be merged (such as {@link ResponseObject#description}
     * @param secondary The other responses object
     * @return A builder that is the merged value of the two given ones
     */
    public static ResponsesObjectBuilder mergeResponses(ResponsesObject primary, ResponsesObject secondary) {
        Set<String> allCodes = new HashSet<>(primary.httpStatusCodes.keySet());
        allCodes.addAll(secondary.httpStatusCodes.keySet());
        Map<String, ResponseObject> mergedStatusCodes = new HashMap<>();
        for (String code : allCodes) {
            mergedStatusCodes.put(code, ResponseObjectBuilder.mergeResponses(
                primary.httpStatusCodes.get(code), secondary.httpStatusCodes.get(code)
            ).build());
        }
        return responsesObject()
            .withHttpStatusCodes(mergedStatusCodes)
            .withDefaultValue(Mutils.coalesce(primary.defaultValue, secondary.defaultValue));
    }


}