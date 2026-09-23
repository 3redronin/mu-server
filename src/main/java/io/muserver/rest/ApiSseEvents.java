package io.muserver.rest;

import java.lang.annotation.*;

/** Container for repeatable {@link ApiSseEvent} declarations. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface ApiSseEvents {
    /** @return the event declarations */
    ApiSseEvent[] value();
}
