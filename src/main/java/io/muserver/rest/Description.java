package io.muserver.rest;

import java.lang.annotation.*;

/**
 * Provides a description of a class, method, or parameter for use in documentation.
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Description {

    /**
     * A short plaintext summary.
     * @return The summary
     */
    String value();

    /**
     * An optional, detailed description that may include <a href="http://spec.commonmark.org/">CommonMark Markdown</a>.
     * @return The description
     */
    String details() default "";

    /**
     * An optional URL pointing to more documentation for this.
     * @return The URL
     */
    String documentationUrl() default "";

    /**
     * An example value
     * @return An example value
     */
    String example() default "";
}
