package io.muserver.rest;

import java.lang.annotation.*;

/**
 * Specifies that a parameter in a rest method is required
 */
@Target({ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Required {

}
