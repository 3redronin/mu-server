package io.muserver.rest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes multiple response codes and descriptions for an API method, for documentation purposes.
 *
 * @see ApiResponse
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiResponses {
    /**
     * A list of {@link ApiResponse}s provided by the API operation.
     *
     * @return the responses
     */
    ApiResponse[] value();
}
