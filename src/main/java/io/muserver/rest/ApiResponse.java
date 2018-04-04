package io.muserver.rest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 *
 * @see ApiResponses
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ApiResponse {
    /**
     * The HTTP status code of the response. This is a String to allow values such as "2XX" to cover all 200-299 codes.
     * @return The code
     */
    String code();

    /**
     * A short description of the response.
     *                    <a href="http://spec.commonmark.org/">CommonMark syntax</a> MAY be used for rich text representation.
     * @return The description
     */
    String message();


    /**
     * A list of possible headers provided alongside the response.
     *
     * @return a list of response headers.
     */
    ResponseHeader[] responseHeaders() default {};

    /**
     * The type of the repsonse body.
     * @return The type
     */
    Class<?> response() default Void.class;

}
