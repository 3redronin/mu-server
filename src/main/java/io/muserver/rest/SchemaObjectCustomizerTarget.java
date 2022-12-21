package io.muserver.rest;

import io.muserver.openapi.SchemaObject;

/**
 * The aspect of the API that a {@link SchemaObject} refers to
 */
public enum SchemaObjectCustomizerTarget {
    /**
     * The request body
     */
    REQUEST_BODY,

    /**
     * The response body
     */
    RESPONSE_BODY,

    /**
     * A form parameter
     */
    FORM_PARAM
}
