package io.muserver.rest;

import java.lang.annotation.*;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

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
    Class<?> response() default ApiResponseObj.DEFAULT.class;

    /**
     * The content type for this code, if different from the default
     * @return A content type such as <code>text/plain</code>
     */
    String[] contentType() default {};

    /**
     * An example return value
     * @return An example value
     */
    String example() default "";

}
class ApiResponseObj {
    static final class DEFAULT {}

    final String code;
    final String message;
    final ResponseHeader[] responseHeaders;
    final Class<?> response;
    final Type genericReturnType;
    final String[] contentType;
    final String example;
    public ApiResponseObj(String code, String message, ResponseHeader[] responseHeaders, Class<?> response, Type genericReturnType, String[] contentType, String example) {
        this.code = code;
        this.message = message;
        this.responseHeaders = responseHeaders;
        this.response = response;
        this.genericReturnType = genericReturnType;
        this.contentType = contentType;
        this.example = example;
    }
    public static ApiResponseObj fromAnnotation(ApiResponse api, Method methodHandle) {
        return new ApiResponseObj(api.code(), api.message(), api.responseHeaders(), api.response() == DEFAULT.class ? methodHandle.getReturnType() : api.response(), null, api.contentType(), api.example());
    }
}