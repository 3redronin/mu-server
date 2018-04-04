package io.muserver.rest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes an HTTP Header that is returned by a rest method.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ResponseHeader {
    /**
     * The header name.
     * @return name
     */
    String name();

    /**
     * A brief description of the header. This could contain examples of use.
     *                    <a href="http://spec.commonmark.org/">CommonMark syntax</a> MAY be used for rich text representation.
     * @return description
     */
    String description() default "";

    /**
     * Marks this header as deprecated to indicate that this header should no longer be used by clients.
     * @return True if deprecated
     */
    boolean deprecated() default false;

    /**
     * An example of a value that will be returned
     * @return The example
     */
    String example() default "";
}