package io.muserver.rest;

import io.muserver.openapi.SchemaObject;

/**
 * The aspect of the API that a {@link SchemaObject} refers to
 */
public enum SchemaObjectCustomizerTarget {
    REQUEST_BODY, RESPONSE_BODY, FORM_PARAM
}
