package io.muserver.rest;

import java.lang.annotation.*;

/**
 * <p>Describes a response code and description for an API method, for documentation purposes.</p>
 * <p>Multiple annotations can be added to cover multiple response types.</p>
 * @see ApiResponses
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ApiResponses.class)
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
     * The type of the response body.
     * @return The type
     */
    Class<?> response() default Void.class;

    /**
     * The content type for this code, if different from the default
     * @return A content type such as <code>text/plain</code>
     */
    String[] contentType() default {};
}
